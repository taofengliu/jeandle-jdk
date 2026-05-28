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

#include "jeandle/jeandleIntrinsicPolicy.hpp"
#include "jeandle/jeandleIntrinsicSupport.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"

static JeandleIRSemanticPlan make_plan(const JeandleIntrinsicDescriptor& desc,
                                       JeandleIntrinsicImplKind impl_kind) {
  const bool is_pure_llvm = (impl_kind == JeandleIntrinsicImplKind::IRInstruction ||
                             impl_kind == JeandleIntrinsicImplKind::LLVMBuiltinCall);
  const bool is_runtime_call = (impl_kind == JeandleIntrinsicImplKind::HotspotStub ||
                                impl_kind == JeandleIntrinsicImplKind::SharedRuntime);

  JeandleIRSemanticPlan plan{};

  // Any safepoint-capable, deopt-capable, or throwing path needs interpreter
  // state so HotSpot can deopt or unwind from the emitted call site.
  plan.attach_deopt_bundle = desc.may_deopt() || desc.needs_gc_state() ||
                             desc.needs_exception_edge();

  // gc-leaf-function asserts that this call site does not enter a safepoint;
  // RewriteStatepointsForGC reads it to skip statepoint insertion. It says
  // nothing about memory reads / writes (those are described separately via
  // MEM_READ / MEM_WRITE for LLVM `memory()` attribute generation).
  plan.attach_gc_leaf_attr = !desc.needs_gc_state() &&
                             !desc.may_deopt() &&
                             !desc.needs_exception_edge() &&
                             (is_pure_llvm || is_runtime_call);

  plan.needs_exception_edge = desc.needs_exception_edge();
  return plan;
}

static JeandleIntrinsicDecision make_decision(const JeandleIntrinsicDescriptor& desc,
                                              JeandleIntrinsicImplKind kind) {
  return {true, kind, make_plan(desc, kind)};
}

static JeandleIntrinsicDecision unsupported(const JeandleIntrinsicDescriptor& desc) {
  return {false, JeandleIntrinsicImplKind::Unsupported,
          make_plan(desc, JeandleIntrinsicImplKind::Unsupported)};
}

// Pick the best available HotSpot runtime path, preferring the dedicated stub
// over the generic SharedRuntime entry.  Returns Unsupported if neither exists.
static JeandleIntrinsicImplKind try_hotspot_path(const JeandleIntrinsicCapabilities& caps) {
  if (caps.has_hotspot_stub)   return JeandleIntrinsicImplKind::HotspotStub;
  if (caps.has_shared_runtime) return JeandleIntrinsicImplKind::SharedRuntime;
  return JeandleIntrinsicImplKind::Unsupported;
}

JeandleIntrinsicDecision JeandleIntrinsicPolicy::refine(const JeandleIntrinsicDecision& base,
                                                        const JeandleIntrinsicDescriptor& desc,
                                                        JeandleIntrinsicImplKind refined_kind) {
  return {base.supported, refined_kind, make_plan(desc, refined_kind)};
}

JeandleIntrinsicDecision JeandleIntrinsicPolicy::decide(const JeandleIntrinsicDescriptor& desc) const {
  // Pure descriptor → impl_kind mapping. Trap-throttle is a runtime-state
  // concern handled by the caller (JeandleAbstractInterpreter::try_lower_intrinsic)
  // via the interpreter's per-compilation trap accumulator.
  switch (desc.lowering_kind) {
    case JeandleLoweringKind::PureIRInstruction:
      // Bare LLVM IR instruction (bitcast, fence). Unconditionally supported,
      // no capability query needed.
      return make_decision(desc, JeandleIntrinsicImplKind::IRInstruction);

    case JeandleLoweringKind::PureLLVMBuiltin:
      // Named llvm.* builtin or platform-specific LLVM target intrinsic.
      // Unconditionally supported, no capability query needed.
      return make_decision(desc, JeandleIntrinsicImplKind::LLVMBuiltinCall);

    case JeandleLoweringKind::RuntimeCall:
    case JeandleLoweringKind::GuardedHybrid: {
      // RuntimeCall and GuardedHybrid share identical policy logic: a HotSpot
      // path wins only when it is preferred AND an actual runtime path exists,
      // otherwise the LLVM builtin. The two descriptor kinds differ only in the
      // lowering function body — a GuardedHybrid lowering (e.g. lower_pow_hybrid)
      // emits its own fast/slow guard, a RuntimeCall lowering is a straight
      // call — which policy does not influence. The distinct names are kept as a
      // descriptor-level semantic label.
      JeandleIntrinsicCapabilities caps = JeandleIntrinsicSupport::query(desc);
      if (caps.hotspot_preferred && caps.any_runtime()) {
        JeandleIntrinsicImplKind k = try_hotspot_path(caps);
        if (k != JeandleIntrinsicImplKind::Unsupported) return make_decision(desc, k);
      }
      if (caps.has_llvm_builtin) return make_decision(desc, JeandleIntrinsicImplKind::LLVMBuiltinCall);
      return unsupported(desc);
    }

    case JeandleLoweringKind::JavaOperation:
      if (desc.java_op_name == nullptr) {
        return unsupported(desc);
      }
      return make_decision(desc, JeandleIntrinsicImplKind::JavaOperation);
  }

  return unsupported(desc);
}
