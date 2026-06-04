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

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allocation.hpp"

// Translate the call-site contract's memory flags into an LLVM `memory()` call-site
// attribute.  Only applied when the call is safe from LLVM's reordering
// perspective: no GC-state observation, no deopt, no exception edge — anything
// that could imply a safepoint or surprise the optimizer must be excluded.
//
// LLVM builtin intrinsics (@llvm.sqrt, @llvm.fabs, ...) already carry the correct
// memory attribute upstream, so this only adds value for *external* runtime stubs
// whose body LLVM cannot see, enabling LICM / GVN / DCE on hot pure libm calls
// and read-only array scans.
void JeandleIntrinsicIRSemantics::apply_memory_attr(llvm::CallBase* call, const JeandleCallSiteContract& contract) {
  if (contract.needs_gc_state() || contract.may_deopt() || contract.needs_exception_edge()) {
    return;
  }
  if (contract.only_orders_memory()) {
    // Ordering-only intrinsics are currently LK_LLVM fences (no call site) and
    // never reach here; guard anyway in case a future contract pairs
    // MEM_ORDERING_ONLY with a call-shaped lowering.
    return;
  }
  const bool reads = contract.reads_memory();
  const bool writes = contract.writes_memory();
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

// Stamp the gc-leaf-function attribute on a call — the single source of the attribute
// string, shared by annotate_call and emit_gc_leaf_inline_asm.  File-local: nothing
// outside this translation unit needs it.
static void mark_gc_leaf(llvm::CallBase* call) {
  llvm::LLVMContext& ctx = call->getContext();
  call->addFnAttr(llvm::Attribute::get(ctx, "gc-leaf-function"));
}

llvm::CallInst* JeandleIntrinsicIRSemantics::emit_gc_leaf_inline_asm(
    llvm::IRBuilder<>& builder,
    llvm::FunctionType* fn_ty,
    llvm::StringRef asm_string,
    llvm::StringRef constraints,
    llvm::ArrayRef<llvm::Value*> args) {
  llvm::InlineAsm* ia = llvm::InlineAsm::get(fn_ty, asm_string, constraints,
                                             /*hasSideEffects=*/true);
  llvm::CallInst* call = builder.CreateCall(ia, args);
  mark_gc_leaf(call);
  return call;
}

void JeandleIntrinsicIRSemantics::annotate_call(llvm::CallBase* call,
                                                const JeandleIntrinsicDescriptor& desc,
                                                const JeandleCallSiteContract& contract,
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
  // consumer.  Only the functional gc-leaf-function attribute remains.
  //
  // gc-leaf-function asserts the call does not enter a safepoint: stamp it when the
  // contract's flags say so (no GC state, deopt, or unwind), or the runtime entry is
  // itself a known leaf routine.
  if (contract.gc_leaf_by_flags() || (entry != nullptr && entry->is_gc_leaf)) {
    mark_gc_leaf(call);
  }
}
