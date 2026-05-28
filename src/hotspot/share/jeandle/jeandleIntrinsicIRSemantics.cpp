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

#include <string>

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicEntrypoints.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allocation.hpp"

static const char* lowering_mode_name(const JeandleIntrinsicDecision& decision,
                                      const JeandleIntrinsicDescriptor& desc) {
  const bool needs_unwind_edge = desc.needs_exception_edge();
  switch (decision.impl_kind) {
    case JeandleIntrinsicImplKind::IRInstruction:
    case JeandleIntrinsicImplKind::LLVMBuiltinCall:
      return needs_unwind_edge ? "managed-runtime-invoke" : "pure-llvm";
    case JeandleIntrinsicImplKind::HotspotStub:
    case JeandleIntrinsicImplKind::SharedRuntime:
      if (needs_unwind_edge) return "managed-runtime-invoke";
      return (desc.needs_gc_state() || desc.may_deopt())
                 ? "managed-runtime-call"
                 : "leaf-runtime-call";
    case JeandleIntrinsicImplKind::JavaOperation:
      return "java-op-call";
    case JeandleIntrinsicImplKind::Unsupported:
      return "unsupported";
  }
  return "unknown";
}

static const char* barrier_kind_name(const JeandleIntrinsicDescriptor& desc) {
  if (desc.weak_referent_load_barrier()) return "weak-referent-load";
  if (desc.raw_referent_read_barrier()) return "raw-referent-read";
  if (desc.card_mark_post_barrier()) return "card-mark-post";
  if (desc.volatile_load_barrier()) return "volatile-load";
  if (desc.volatile_store_barrier()) return "volatile-store";
  return "none";
}

// Attach a single-string metadata node to any instruction.
static void set_str_metadata(llvm::Instruction& inst, llvm::StringRef key, llvm::StringRef value) {
  llvm::LLVMContext& ctx = inst.getContext();
  inst.setMetadata(key, llvm::MDNode::get(ctx, {llvm::MDString::get(ctx, value)}));
}

llvm::SmallVector<llvm::OperandBundleDef, 1>
JeandleIntrinsicIRSemantics::build_operand_bundles(JeandleAbstractInterpreter* interp,
                                                   const JeandleIRSemanticPlan& plan) {
  llvm::SmallVector<llvm::OperandBundleDef, 1> bundles;
  if (plan.attach_deopt_bundle) {
    bundles.push_back(interp->create_current_deopt_bundle());
  }
  return bundles;
}

void JeandleIntrinsicIRSemantics::annotate_instruction(llvm::Instruction& inst,
                                                       const JeandleIntrinsicDescriptor& desc,
                                                       const JeandleIntrinsicDecision& decision,
                                                       const JeandleIntrinsicEntrypoint* entry) {
  set_str_metadata(inst, "jeandle.intrinsic.id",
                   std::to_string(vmIntrinsics::as_int(desc.id)));
  set_str_metadata(inst, "jeandle.lowering.mode",
                   lowering_mode_name(decision, desc));
  set_str_metadata(inst, "jeandle.semantic.barrier_kind",
                   barrier_kind_name(desc));

  if (auto* call = llvm::dyn_cast<llvm::CallBase>(&inst)) {
    llvm::LLVMContext& ctx = inst.getContext();
    if (decision.ir_plan.attach_gc_leaf_attr || (entry != nullptr && entry->is_gc_leaf)) {
      call->addFnAttr(llvm::Attribute::get(ctx, "gc-leaf-function"));
    }
    if (entry != nullptr && entry->well_known_name != nullptr) {
      set_str_metadata(inst, "jeandle.runtime.entry", entry->well_known_name);
    }
  }
}
