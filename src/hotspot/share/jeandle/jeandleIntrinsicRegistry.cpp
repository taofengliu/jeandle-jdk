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

#include "jeandle/jeandleIntrinsicRegistry.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"

// =============================================================================
// How to add a new intrinsic to Jeandle
// -----------------------------------------------------------------------------
// The framework has four touch points: descriptor (this file), support, policy,
// and lowering. A new intrinsic almost always touches descriptor + lowering and
// sometimes support; policy is rarely changed.
//
// 1. Pick a JeandleLoweringKind that fits the intrinsic's shape:
//      PureIRInstruction — bare LLVM IR (bitcast, fence). No runtime path.
//      PureLLVMBuiltin   — named llvm.* builtin or target intrinsic (sqrt, abs,
//                          ctpop). No runtime path.
//      RuntimeCall       — call into a HotSpot stub or SharedRuntime entry.
//      GuardedHybrid     — runtime path is preferred when available but the
//                          lower_* helper itself emits a fast/slow guard
//                          (e.g. lower_pow_hybrid).
//      JavaOperation     — call into a Jeandle-defined JavaOp; supply
//                          java_op_name. Use this for intrinsics that need
//                          full IR-level semantics (GC barriers, type checks).
//
// 2. Add a JeandleIntrinsicDescriptor entry to _intrinsic_table below.
//    Field guide (see JeandleIntrinsicDescriptor for full definitions):
//      id              — vmIntrinsics::_xxx, must satisfy vmIntrinsics::is_valid_id
//      lowering_kind   — one of the kinds above
//      support_flags   — bitmask of SUPPORT_LLVM_INTRIN / SUPPORT_HOTSPOT_STUB.
//      needs_gc_state  — true if the call may observe heap state during a GC
//                        (forces statepoint bundle attachment).
//      may_deopt       — true if the lowering may emit uncommon_trap.
//      needs_exception_edge — true if the call can throw a Java exception and
//                        must be lowered as `invoke`.
//      trap_throttle_mask — bitmask of Deoptimization::DeoptReason values that
//                        cause the intrinsic to be marked Unsupported when the
//                        caller has hit too_many_traps at this bci.
//      java_op_name    — required iff lowering_kind == JavaOperation.
//
// 3. jeandleIntrinsicSupport.cpp — add a probe for the HotSpot stub or
//    SharedRuntime entry if support_flags advertises one. Pure IR and pure
//    JavaOp intrinsics need no change here.
//
// 4. jeandleIntrinsicPolicy.cpp — usually no change. decide() already handles
//    every JeandleLoweringKind. Only touch it if you introduce a new strategy.
//
// 5. jeandleIntrinsicLowering.cpp — add `case vmIntrinsics::_yourId:` to lower()
//    routing to an existing lower_* helper (when the body shape is already
//    covered) or write a new leaf handler. Reuse the shared emit helpers
//    (emit_runtime_call / emit_runtime_invoke / emit_java_op_call /
//    emit_java_op_invoke / annotate_generated_instruction) where possible.
//
// 6. For JavaOperation intrinsics: define the JavaOp body in either
//    templatemodule/template.ll or jeandleRuntimeDefinedJavaOps.cpp.
//
// 7. Add a jtreg test under test/hotspot/jtreg/compiler/jeandle/. The
//    `jeandle.lowering.mode` metadata attached by annotate_generated_instruction
//    is a stable hook for IR-level assertions.
// =============================================================================

static constexpr JeandleTrapReasonMask trap_reason_mask(Deoptimization::DeoptReason reason) {
  return JeandleTrapReasonMask(1u) << static_cast<uint>(reason);
}

#ifdef ASSERT
static void validate_descriptor(const JeandleIntrinsicDescriptor& desc) {
  assert(desc.id != vmIntrinsics::_none && vmIntrinsics::is_valid_id(desc.id),
         "invalid Jeandle intrinsic id");
  assert((desc.lowering_kind != JeandleLoweringKind::PureIRInstruction &&
          desc.lowering_kind != JeandleLoweringKind::PureLLVMBuiltin) ||
         !desc.needs_exception_edge(),
         "pure-IR lowering kinds cannot require an exception edge");
  assert(desc.trap_throttle_mask == 0 || desc.may_deopt(),
         "trap throttling requires a deopt-capable intrinsic");
  assert(desc.lowering_kind != JeandleLoweringKind::JavaOperation || desc.java_op_name != nullptr,
         "JavaOperation descriptor must have a non-null java_op_name");
  assert(desc.java_op_name == nullptr || desc.java_op_name[0] != '\0',
         "empty JavaOp name string");
  assert(!desc.only_orders_memory() || (!desc.reads_memory() && !desc.writes_memory()),
         "MEM_ORDERING_ONLY is mutually exclusive with MEM_READ / MEM_WRITE");
  assert(desc.barrier_semantics() == 0 ||
         (desc.barrier_semantics() & (desc.barrier_semantics() - 1)) == 0,
         "barrier semantics are mutually exclusive");
  assert(!desc.weak_referent_load_barrier() ||
         (desc.reads_memory() && desc.needs_gc_state() && !desc.writes_memory()),
         "weak referent load barrier requires read-only GC-visible memory");
  assert(!desc.raw_referent_read_barrier() ||
         (desc.reads_memory() && desc.needs_gc_state() && !desc.writes_memory()),
         "raw referent read barrier requires read-only GC-visible memory");
  assert(!desc.card_mark_post_barrier() ||
         (desc.writes_memory() && desc.needs_gc_state()),
         "card mark post barrier requires GC-visible memory writes");
  assert(!desc.volatile_load_barrier() ||
         (desc.reads_memory() && !desc.writes_memory()),
         "volatile load barrier requires read-only memory effects");
  assert(!desc.volatile_store_barrier() ||
         desc.writes_memory(),
         "volatile store barrier requires memory writes");
  switch (desc.id) {
    case vmIntrinsics::_Reference_get:
      assert(desc.weak_referent_load_barrier(),
             "Reference.get requires weak referent load barrier semantics");
      break;
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:
      assert(desc.raw_referent_read_barrier(),
             "refersTo0 requires raw referent read barrier semantics");
      break;
    default:
      break;
  }
}
#endif

class JeandleIntrinsicRegistryTable : public AllStatic {
 public:
  static constexpr const JeandleIntrinsicDescriptor* table_begin() {
    return &_intrinsic_table[0];
  }

  static constexpr const JeandleIntrinsicDescriptor* table_end() {
    return &_intrinsic_table[ARRAY_SIZE(_intrinsic_table)];
  }

 private:
  // Descriptor fields, in order:
  //   id
  //   control_flags  (bitmask of CTRL_*)
  //   memory_flags   (bitmask of MEM_*)
  //   lowering_kind
  //   support_flags  (bitmask of SUPPORT_*)
  //   java_op_name
  //   trap_throttle_mask
  //
  // Flag literals are self-describing at the call site, so the table reads as a
  // declarative list of facts about each intrinsic without consulting struct
  // definitions for what each bool position means.
  static constexpr JeandleIntrinsicDescriptor _intrinsic_table[] = {
    { vmIntrinsics::_dabs,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_fabs,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_iabs,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_labs,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_bitCount_i,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_bitCount_l,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_dsqrt,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_dsqrt_strict,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_LLVM_INTRIN,                nullptr },

    // Rounding: GuardedHybrid because a native instruction is required for
    // correctness/performance (SSE4.1 ROUNDSD on x86, FRINT* on AArch64).
    // JeandleIntrinsicSupport::query() checks the CPU feature at decision time;
    // if absent, any_path() returns false and the call is not intrinsified.
    { vmIntrinsics::_floor,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::GuardedHybrid,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_ceil,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::GuardedHybrid,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_rint,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::GuardedHybrid,
      SUPPORT_LLVM_INTRIN,                nullptr },

    { vmIntrinsics::_floatToRawIntBits,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_intBitsToFloat,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_doubleToRawLongBits,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_LLVM_INTRIN,                nullptr },
    { vmIntrinsics::_longBitsToDouble,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_LLVM_INTRIN,                nullptr },

    { vmIntrinsics::_dsin,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dcos,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dtan,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dlog,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dlog10,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dexp,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },
    { vmIntrinsics::_dpow,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::GuardedHybrid,
      SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN, nullptr },

    // System hints
    { vmIntrinsics::_onSpinWait,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_NONE,                       nullptr },

    // _blackhole: optimizer constraint — consume all arguments to prevent DCE, return void.
    // Uses volatile inline asm per argument so LLVM cannot eliminate the argument computations.
    // PureLLVMBuiltin: always supported, no deopt, no memory effects.
    { vmIntrinsics::_blackhole,
      CTRL_NONE,                          MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_NONE,                       nullptr },

    // Preconditions.checkIndex(int index, int length, BiFunction exceptionFactory) -> int
    //
    // Emits a single unsigned comparison (ICMP_UGE) that covers both index < 0 and
    // index >= length in one check, then branches to a DeoptTrap on failure.
    // The BiFunction callback argument is popped and discarded in the fast path; if
    // the guard fires the interpreter re-executes the full method and invokes it.
    //
    // C2 behaviour reference: library_call.cpp checks too_many_traps for both
    // Reason_intrinsic (length < 0) and Reason_range_check (index OOB); we
    // mirror the same site throttle via trap_throttle_mask.
    { vmIntrinsics::_Preconditions_checkIndex,
      CTRL_MAY_DEOPT,                     MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_NONE,                       nullptr,
      trap_reason_mask(Deoptimization::Reason_intrinsic) |
          trap_reason_mask(Deoptimization::Reason_range_check) },

    // Preconditions.checkIndex(long index, long length, BiFunction exceptionFactory) -> long
    // Identical trap semantics to the int variant; only the value width differs.
    { vmIntrinsics::_Preconditions_checkLongIndex,
      CTRL_MAY_DEOPT,                     MEM_NONE,
      JeandleLoweringKind::PureLLVMBuiltin,
      SUPPORT_NONE,                       nullptr,
      trap_reason_mask(Deoptimization::Reason_intrinsic) |
          trap_reason_mask(Deoptimization::Reason_range_check) },

    // Object.getClass(): loads the java.lang.Class mirror via the klass's OopHandle.
    // TypeSemantic + JavaOperation: the two-level load (klass → OopHandle → mirror)
    // is implemented as jeandle.get_class.
    //
    // Memory: MEM_READ | MEM_NEEDS_GC_STATE — three loads (header, OopHandle,
    // mirror oop); the OopStorage load must stay visible to GC statepoint code.
    //
    // Receiver null-check responsibility: invokevirtual/invokeinterface bytecodes
    // already null-check the receiver before dispatch, so this lowering path
    // assumes a non-null object on the stack.  If getClass is ever lowered via a
    // non-invoke path (inlined JavaOp, direct IR), a null check must be added at
    // that callsite or inside the JavaOp itself.
    //
    { vmIntrinsics::_getClass,
      CTRL_NONE,                          MEM_READ | MEM_NEEDS_GC_STATE,
      JeandleLoweringKind::JavaOperation,
      SUPPORT_NONE,                       "jeandle.get_class" },

    // Reference.get(): returns the referent and applies the needed GC load barrier in the JavaOp.
    // CTRL_NONE — no speculative guard; attach_deopt_bundle is plan-driven by
    // MEM_NEEDS_GC_STATE, not by deoptimization semantics.
    { vmIntrinsics::_Reference_get,
      CTRL_NONE,                          MEM_READ | MEM_NEEDS_GC_STATE | MEM_BARRIER_WEAK_REFERENT_LOAD,
      JeandleLoweringKind::JavaOperation,
      SUPPORT_NONE,                       "jeandle.reference_get" },

    // Reference.refersTo0(): raw referent pointer identity comparison (no GC barrier).
    { vmIntrinsics::_Reference_refersTo0,
      CTRL_NONE,                          MEM_READ | MEM_NEEDS_GC_STATE | MEM_BARRIER_RAW_REFERENT_READ,
      JeandleLoweringKind::JavaOperation,
      SUPPORT_NONE,                       "jeandle.reference_refers_to" },

    // Memory fences: lower to LLVM fence instructions (acquire / release / seq_cst).
    { vmIntrinsics::_loadFence,
      CTRL_NONE,                          MEM_ORDERING_ONLY,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_NONE,                       nullptr },
    { vmIntrinsics::_storeFence,
      CTRL_NONE,                          MEM_ORDERING_ONLY,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_NONE,                       nullptr },
    { vmIntrinsics::_fullFence,
      CTRL_NONE,                          MEM_ORDERING_ONLY,
      JeandleLoweringKind::PureIRInstruction,
      SUPPORT_NONE,                       nullptr },

    // PhantomReference.refersTo0 shares semantics with Reference.refersTo0:
    // raw referent read (no GC barrier), pointer identity comparison.
    { vmIntrinsics::_PhantomReference_refersTo0,
      CTRL_NONE,                          MEM_READ | MEM_NEEDS_GC_STATE | MEM_BARRIER_RAW_REFERENT_READ,
      JeandleLoweringKind::JavaOperation,
      SUPPORT_NONE,                       "jeandle.reference_refers_to" },

    // Array.newInstance(Class<?> componentType, int length) → Object
    //
    // The JavaOp jeandle.new_array loads the cached array klass from the
    // component-type mirror and calls new_array on the fast path; if the klass is
    // not yet cached it falls back to Reflection::reflect_new_array.
    //
    // CTRL_NEEDS_EXCEPTION_EDGE: NegativeArraySizeException / NullPointerException /
    //   IllegalArgumentException may be thrown by the runtime.
    // MEM_READ | MEM_WRITE (no MEM_NEEDS_GC_STATE): reads klass mirror, writes
    //   the newly allocated object header/elements; the runtime call handles
    //   allocation-time GC interaction, no per-lowering barrier required.
    { vmIntrinsics::_newArray,
      CTRL_NEEDS_EXCEPTION_EDGE,          MEM_READ | MEM_WRITE,
      JeandleLoweringKind::JavaOperation,
      SUPPORT_NONE,                       "jeandle.new_array" },

    // StringCoding.countPositives(byte[] ba, int off, int len) → int
    //
    // Returns the number of leading bytes in ba[off..off+len) with bit 7 clear.
    // RuntimeCall: at startup, generate_count_positives_adapter() installs a
    // platform-native SIMD stub adapter; if absent the entrypoint layer falls back
    // to the scalar count_positives_impl.
    //
    // CTRL_MAY_DEOPT: precondition guards (null, off<0, len<0, off+len>length)
    //   emit uncommon_trap(Reason_intrinsic) which requires a deopt bundle so the
    //   interpreter can re-execute and throw IOOBE.
    { vmIntrinsics::_countPositives,
      CTRL_MAY_DEOPT,                     MEM_READ,
      JeandleLoweringKind::RuntimeCall,
      SUPPORT_HOTSPOT_STUB,               nullptr,
      trap_reason_mask(Deoptimization::Reason_intrinsic) },
  };
};

constexpr JeandleIntrinsicDescriptor JeandleIntrinsicRegistryTable::_intrinsic_table[];

const JeandleIntrinsicDescriptor*
JeandleIntrinsicRegistry::_lookup[(int)vmIntrinsics::ID_LIMIT];

#ifdef ASSERT
bool JeandleIntrinsicRegistry::_initialized = false;
#endif

void JeandleIntrinsicRegistry::initialize() {
  // _lookup has static storage duration and is already zero-initialized (all nullptr)
  // by the C++ runtime before this function runs.  No explicit clear needed.

  for (const JeandleIntrinsicDescriptor* it = JeandleIntrinsicRegistryTable::table_begin();
       it != JeandleIntrinsicRegistryTable::table_end();
       ++it) {
    DEBUG_ONLY(validate_descriptor(*it);)
    const int index = vmIntrinsics::as_int(it->id);
    assert(_lookup[index] == nullptr, "duplicate Jeandle intrinsic descriptor");
    _lookup[index] = it;
  }

#ifdef ASSERT
  _initialized = true;
#endif
}

const JeandleIntrinsicDescriptor* JeandleIntrinsicRegistry::lookup(vmIntrinsics::ID id) {
  assert(_initialized, "JeandleIntrinsicRegistry must be initialized first");
  if (!vmIntrinsics::is_valid_id(id)) {
    return nullptr;
  }
  return _lookup[vmIntrinsics::as_int(id)];
}

const JeandleIntrinsicDescriptor* JeandleIntrinsicRegistry::lookup(const ciMethod* method) {
  return method == nullptr ? nullptr : lookup(method->intrinsic_id());
}
