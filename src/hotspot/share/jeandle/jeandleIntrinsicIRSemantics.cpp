/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

#include "jeandle/jeandleIntrinsicIRSemantics.hpp"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicEntrypoints.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allocation.hpp"

// Translate the call_info's memory flags into an LLVM `memory()` call-site
// attribute.  Only applied when the call is safe from LLVM's reordering
// perspective: no GC-state observation, no deopt, no exception edge — anything
// that could imply a safepoint or surprise the optimizer must be excluded.
//
// LLVM builtin intrinsics (@llvm.sqrt, @llvm.fabs, ...) already carry the correct
// memory attribute upstream, so this only adds value for *external* runtime stubs
// whose body LLVM cannot see, enabling LICM / GVN / DCE on hot pure libm calls
// and read-only array scans.
void JeandleIntrinsicIRSemantics::apply_memory_attr(llvm::CallBase* call, const JeandleCallInfo& ci) {
  if (ci.needs_gc_state() || ci.may_deopt() || ci.needs_exception_edge()) {
    return;
  }
  if (ci.only_orders_memory()) {
    // Ordering-only intrinsics are fences (PureLLVM, no call_info) and never
    // reach here; guard anyway in case a future descriptor pairs MEM_ORDERING_ONLY
    // with a call-shaped lowering.
    return;
  }
  const bool reads = ci.reads_memory();
  const bool writes = ci.writes_memory();
  if (!reads && !writes) {
    call->setDoesNotAccessMemory();   // memory(none)
  } else if (reads && !writes) {
    call->setOnlyReadsMemory();        // memory(read)
  } else if (!reads && writes) {
    call->setOnlyWritesMemory();       // memory(write)
  }
  // reads && writes: default IR semantics already cover read+write — adding the
  // attribute would narrow nothing.
}

llvm::SmallVector<llvm::OperandBundleDef, 1>
JeandleIntrinsicIRSemantics::build_operand_bundles(JeandleAbstractInterpreter* interp,
                                                   bool attach_deopt_bundle) {
  llvm::SmallVector<llvm::OperandBundleDef, 1> bundles;
  if (attach_deopt_bundle) {
    bundles.push_back(interp->create_current_deopt_bundle());
  }
  return bundles;
}

void JeandleIntrinsicIRSemantics::annotate_instruction(llvm::Instruction& inst,
                                                       const JeandleIntrinsicDescriptor& desc,
                                                       const JeandleIntrinsicEntrypoint* entry) {
  // TODO(barrier-hook): desc.barrier_kind carries the GC barrier semantic that a
  // future late GC-barrier LLVM pass needs (analogous to the array GC barrier
  // late insertion).  How that semantic is threaded to LLVM is NOT decided yet
  // (named metadata on the call site? an operand bundle? a marker intrinsic?), so
  // nothing is emitted here.  Until that contract is settled, the G1 SATB
  // pre-barrier for Reference.get stays inside the JavaOp body for correctness.
  // When the contract lands, emit it from desc.barrier_kind here.
  //
  // The observability-only metadata (jeandle.intrinsic.id / jeandle.lowering.mode
  // / jeandle.runtime.entry) was dropped in the call-shape refactor; it had no
  // consumer.  Only the functional gc-leaf-function attribute remains below.
  auto* call = llvm::dyn_cast<llvm::CallBase>(&inst);
  if (call == nullptr) {
    return;
  }
  // gc-leaf-function asserts the call does not enter a safepoint.  Call/Hybrid
  // intrinsics derive it from their call_info (plus the runtime entry's own leaf
  // flag); PureLLVM intrinsics only ever emit leaf calls (llvm.abs/ctpop,
  // blackhole inline asm, llvm.assume), so they are always gc-leaf.
  const bool gc_leaf = desc.has_call_info()
      ? (desc.call_info->attach_gc_leaf() || (entry != nullptr && entry->is_gc_leaf))
      : true;
  if (gc_leaf) {
    llvm::LLVMContext& ctx = inst.getContext();
    call->addFnAttr(llvm::Attribute::get(ctx, "gc-leaf-function"));
  }
}
