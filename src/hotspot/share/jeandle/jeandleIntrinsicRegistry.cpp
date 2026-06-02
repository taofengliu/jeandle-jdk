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
 */

#include "jeandle/jeandleIntrinsicRegistry.hpp"
#include "jeandle/jeandleIntrinsicCallInfo.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"

// =============================================================================
// How to add a new intrinsic to Jeandle
// -----------------------------------------------------------------------------
// Two layers describe an intrinsic:
//
//   JeandleIntrinsicDescriptor (base, registry.hpp) — admission-time facts:
//       id, lowering_kind, call_info, barrier_kind.  (Trap throttling is a
//       separate id-keyed side-table, kTrapThrottleTable.)
//   JeandleIntrinsicCallInfo (callinfo.hpp) — everything needed only when the lowering
//       emits a call site: control/memory/support flags, callee identity and
//       operand-stack shape.
//
// Most intrinsics are ONE LINE in a table macro below.  Each table macro is
// expanded twice (X-macro): once to define a `static constexpr JeandleIntrinsicCallInfo
// ci_<name>`, once to emit the base-descriptor row pointing at it.  Pick the
// table by *shape*:
//
//   JEANDLE_CALL_LLVM_BUILTIN_TABLE — Call lowered to a single llvm.* builtin,
//       one operand in / one result out, same type.  `V(name, intrinsic, type)`.
//       CPU-feature gating (floor/ceil/rint) lives in Support::query, not here.
//   JEANDLE_CALL_RUNTIME_STUB_TABLE — Call backed by a HotSpot runtime stub /
//       SharedRuntime routine, with the llvm builtin as fallback; the stub /
//       SharedRuntime resolvers are derived from `name` by token paste.
//       `V(name, intrinsic, well_known)`.  NOTE: currently models the single
//       "double in -> double out" shape only (libm math today); a differently
//       shaped runtime-stub intrinsic needs its own row/table.
//   JEANDLE_CALL_JAVAOP_TABLE — Call delegating to a JavaOp.
//       `V(name, java_op, ctrl, mem, barrier, arg_count, result, arg_types...)`.
//   JEANDLE_PURE_TABLE — PureLLVM (bare IR / inline-asm / uncommon_trap); no
//       call site, call_info == nullptr.  `V(name)`.
//
// Intrinsics that do not fit a table (Hybrid bodies, trap-throttled PureLLVM) are
// written out explicitly right after the tables.  After adding a row:
//   - Support::query (jeandleIntrinsicSupport.cpp) — only if a runtime/CPU-gated
//     path is advertised.
//   - Lowering (jeandleIntrinsicLowering.cpp) — Call needs no code; Hybrid /
//     PureLLVM add a `case` routed to a lower_* helper.
//   - JavaOp callees — define the body in template.ll / jeandleRuntimeDefinedJavaOps.cpp.
//   - A jtreg test under test/hotspot/jtreg/compiler/jeandle/.
// =============================================================================

static constexpr JeandleTrapReasonMask trap_reason_mask(Deoptimization::DeoptReason reason) {
  return JeandleTrapReasonMask(1u) << static_cast<uint>(reason);
}

// ---- One-line intrinsic tables (see the header comment for the column guide) ----

// Call lowered to a single llvm.* builtin.  Operand/result types come from the
// intercepted method's signature at lowering time, not from the table.  Columns:
//   vm_name       — vmIntrinsics::_<vm_name> (also the ci_<vm_name> symbol)
//   llvm_builtin  — llvm::Intrinsic::<llvm_builtin> id
#define JEANDLE_CALL_LLVM_BUILTIN_TABLE(V) \
  /*   vm_name      llvm_builtin */ \
  V(dabs,         fabs)  \
  V(fabs,         fabs)  \
  V(bitCount_i,   ctpop) \
  V(dsqrt,        sqrt)  \
  V(dsqrt_strict, sqrt)  \
  V(floor,        floor) \
  V(ceil,         ceil)  \
  V(rint,         rint)

// Call backed by a HotSpot runtime stub / SharedRuntime routine.  The stub and
// SharedRuntime resolvers are derived from vm_name by token paste
// (StubRoutines_<vm_name>_callee / SharedRuntime_<vm_name>_callee); the llvm
// builtin is the fallback when no runtime path is available/preferred.  Arg and
// result types come from the method signature, so this table is shape-agnostic —
// a future multi-arg stub (crc32, AES, ...) adds a row with no new columns.
//   vm_name       — vmIntrinsics::_<vm_name>; also drives the resolver names
//   llvm_builtin  — llvm::Intrinsic::<llvm_builtin> id (builtin fallback)
#define JEANDLE_CALL_RUNTIME_STUB_TABLE(V) \
  /*   vm_name  llvm_builtin */  \
  V(dsin,   sin)   \
  V(dcos,   cos)   \
  V(dtan,   tan)   \
  V(dlog,   log)   \
  V(dlog10, log10) \
  V(dexp,   exp)

// Call delegating to a JavaOp.  Stack shape comes from the method signature.
//   Columns: vm_name, java_op_name, control_flags, memory_flags, barrier_kind
// (Trap throttling is not a column — see the trap-throttle side-table below.)
#define JEANDLE_CALL_JAVAOP_TABLE(V)                                                                     \
  V(getClass,                   "jeandle.get_class",          CTRL_NONE,                 MEM_READ | MEM_NEEDS_GC_STATE, None)             \
  V(Reference_get,              "jeandle.reference_get",      CTRL_NONE,                 MEM_READ | MEM_NEEDS_GC_STATE, WeakReferentLoad) \
  V(Reference_refersTo0,        "jeandle.reference_refers_to", CTRL_NONE,                MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead)  \
  V(PhantomReference_refersTo0, "jeandle.reference_refers_to", CTRL_NONE,                MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead)  \
  V(newArray,                   "jeandle.new_array",          CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE,          None)

// PureLLVM: bare LLVM IR / inline-asm / uncommon_trap; no call site, so
// call_info == nullptr.  Column: vm_name.  (Trap throttling — which a PureLLVM
// body like Preconditions does need — is NOT here; it is a sparse id-keyed
// property kept in the trap-throttle side-table, see kTrapThrottleTable below.)
#define JEANDLE_PURE_TABLE(V) \
  V(iabs)                     \
  V(labs)                     \
  V(bitCount_l)               \
  V(floatToRawIntBits)        \
  V(intBitsToFloat)           \
  V(doubleToRawLongBits)      \
  V(longBitsToDouble)         \
  V(loadFence)                \
  V(storeFence)               \
  V(fullFence)                \
  V(onSpinWait)               \
  V(blackhole)                \
  V(Preconditions_checkIndex) \
  V(Preconditions_checkLongIndex)

// ---- Pass 1: define a JeandleIntrinsicCallInfo per Call/JavaOp row. ----
#define JEANDLE_DEFINE_LLVM_BUILTIN_CALL_INFO(VM_NAME, LLVM_BUILTIN)                  \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                                   \
    CTRL_NONE, MEM_NONE, SUPPORT_LLVM_INTRIN,                                         \
    JeandleIntrinsicCalleeKind::LLVMBuiltin, nullptr, llvm::Intrinsic::LLVM_BUILTIN,           \
    nullptr, nullptr };
JEANDLE_CALL_LLVM_BUILTIN_TABLE(JEANDLE_DEFINE_LLVM_BUILTIN_CALL_INFO)
#undef JEANDLE_DEFINE_LLVM_BUILTIN_CALL_INFO

#define JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO(VM_NAME, LLVM_BUILTIN)                  \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                                   \
    CTRL_NONE, MEM_NONE, SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN,                  \
    JeandleIntrinsicCalleeKind::RuntimeStub, nullptr, llvm::Intrinsic::LLVM_BUILTIN,           \
    &JeandleRuntimeRoutine::StubRoutines_##VM_NAME##_callee,                          \
    &JeandleRuntimeRoutine::SharedRuntime_##VM_NAME##_callee };
JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO)
#undef JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO

#define JEANDLE_DEFINE_JAVAOP_CALL_INFO(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS,         \
                                        MEMORY_FLAGS, BARRIER)                        \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                                   \
    CONTROL_FLAGS, MEMORY_FLAGS, SUPPORT_NONE,                                        \
    JeandleIntrinsicCalleeKind::JavaOp, JAVA_OP_NAME, llvm::Intrinsic::not_intrinsic,          \
    nullptr, nullptr };
JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_DEFINE_JAVAOP_CALL_INFO)
#undef JEANDLE_DEFINE_JAVAOP_CALL_INFO

// --- Hybrid: hand-written JeandleIntrinsicCallInfo (the lowering body pops/pushes the
//     stack itself).  No table macro — these are few and each is shaped
//     differently — so spell out the field order here for reference:
//
//   { control_flags, memory_flags, support_flags,
//     callee_kind, java_op_name, llvm_intrin_id, stub_callee_fn, shared_callee_fn }
//
//   control_flags    — bitmask of JeandleControlFlag (CTRL_*)
//   memory_flags     — bitmask of JeandleMemoryFlag (MEM_*)
//   support_flags    — bitmask of JeandleSupportFlag (SUPPORT_*)
//   callee_kind      — JeandleIntrinsicCalleeKind; None when the body resolves its own callee
//   java_op_name     — JavaOp symbol, else nullptr
//   llvm_intrin_id   — llvm::Intrinsic::ID; not_intrinsic when unused
//   stub/shared_fn   — runtime-callee resolvers (RuntimeStub), else nullptr
//
//   Operand-stack shape is not stored — a Hybrid body pops/pushes explicitly.
//   Trap throttling is not a JeandleIntrinsicCallInfo field either; it is in the id-keyed
//   trap-throttle side-table (kTrapThrottleTable below).
static constexpr JeandleIntrinsicCallInfo ci_dpow = {
  CTRL_NONE, MEM_NONE, SUPPORT_HOTSPOT_STUB | SUPPORT_LLVM_INTRIN,
  JeandleIntrinsicCalleeKind::RuntimeStub, nullptr, llvm::Intrinsic::pow,
  &JeandleRuntimeRoutine::StubRoutines_dpow_callee,
  &JeandleRuntimeRoutine::SharedRuntime_dpow_callee };
// countPositives resolves its callee through resolve_count_positives() inside the
// body, so callee_kind is None; only the flags are consumed (via emit_runtime_call).
static constexpr JeandleIntrinsicCallInfo ci_count_positives = {
  CTRL_MAY_DEOPT, MEM_READ, SUPPORT_HOTSPOT_STUB,
  JeandleIntrinsicCalleeKind::None, nullptr, llvm::Intrinsic::not_intrinsic, nullptr, nullptr };

#ifdef ASSERT
static void validate_descriptor(const JeandleIntrinsicDescriptor& desc) {
  assert(desc.id != vmIntrinsics::_none && vmIntrinsics::is_valid_id(desc.id),
         "invalid Jeandle intrinsic id");
  const JeandleIntrinsicCallInfo* ci = desc.call_info;
  // call_info is present iff the lowering emits a call site (Call or Hybrid).
  assert((desc.lowering_kind == JeandleLoweringKind::PureLLVM) == (ci == nullptr),
         "call_info must be present iff lowering_kind is Call/Hybrid");
  if (ci == nullptr) {
    return;
  }
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::JavaOp || ci->java_op_name != nullptr,
         "JavaOp callee requires a non-null java_op_name");
  assert(ci->java_op_name == nullptr || ci->java_op_name[0] != '\0',
         "empty JavaOp name string");
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::RuntimeStub ||
         (ci->stub_callee_fn != nullptr || ci->shared_callee_fn != nullptr || ci->supports_llvm_intrin()),
         "RuntimeStub needs a runtime resolver or a builtin fallback");
  assert(!ci->only_orders_memory() || (!ci->reads_memory() && !ci->writes_memory()),
         "MEM_ORDERING_ONLY is mutually exclusive with MEM_READ / MEM_WRITE");
  // The referent-reading intrinsics must keep read-only GC-visible memory so the
  // JavaOp body (and a future late barrier pass) can apply or deliberately
  // suppress the GC load barrier.  The barrier_kind is the metadata hook for
  // that pass and must match the intrinsic.
  switch (desc.id) {
    case vmIntrinsics::_Reference_get:
      assert(ci->reads_memory() && ci->needs_gc_state() && !ci->writes_memory(),
             "referent read requires read-only GC-visible memory");
      assert(desc.barrier_kind == JeandleBarrierKind::WeakReferentLoad,
             "Reference.get requires weak referent load barrier annotation");
      break;
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:
      assert(ci->reads_memory() && ci->needs_gc_state() && !ci->writes_memory(),
             "referent read requires read-only GC-visible memory");
      assert(desc.barrier_kind == JeandleBarrierKind::RawReferentRead,
             "refersTo0 requires raw referent read barrier annotation");
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
  // Base descriptor rows, all generated from the one-line tables (pass 2 of the
  // X-macro).  Field order: id, lowering_kind, call_info, barrier_kind.  Hybrid
  // rows are listed last because that group grows as new hand-written intrinsics
  // are added.
  static constexpr JeandleIntrinsicDescriptor _intrinsic_table[] = {
    // ---- Call: LLVM builtin + runtime-stub (barrier-free) ----
#define JEANDLE_ROW_CALL(VM_NAME, ...) \
    { vmIntrinsics::_##VM_NAME, JeandleLoweringKind::Call, &ci_##VM_NAME },
    JEANDLE_CALL_LLVM_BUILTIN_TABLE(JEANDLE_ROW_CALL)
    JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_ROW_CALL)
#undef JEANDLE_ROW_CALL

    // ---- Call: JavaOp (barrier_kind carried from the table) ----
#define JEANDLE_ROW_JAVAOP(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS, MEMORY_FLAGS, BARRIER) \
    { vmIntrinsics::_##VM_NAME, JeandleLoweringKind::Call, &ci_##VM_NAME, JeandleBarrierKind::BARRIER },
    JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_ROW_JAVAOP)
#undef JEANDLE_ROW_JAVAOP

    // ---- PureLLVM (no call site, call_info == nullptr) ----
#define JEANDLE_ROW_PURE(VM_NAME) \
    { vmIntrinsics::_##VM_NAME, JeandleLoweringKind::PureLLVM, nullptr },
    JEANDLE_PURE_TABLE(JEANDLE_ROW_PURE)
#undef JEANDLE_ROW_PURE

    // ---- Hybrid: hand-written bodies (grows over time; keep last) ----
    { vmIntrinsics::_dpow,           JeandleLoweringKind::Hybrid, &ci_dpow },
    { vmIntrinsics::_countPositives, JeandleLoweringKind::Hybrid, &ci_count_positives },
  };
};

// ---------------------------------------------------------------------------
// Trap-throttle side-table: id -> trap_throttle_mask.  Sparse (only intrinsics
// whose lowering can emit uncommon_trap appear here), id-keyed, and independent
// of lowering_kind — any Call/Hybrid/PureLLVM intrinsic that deopts adds a row.
// Anything not listed throttles on nothing (mask 0).
// ---------------------------------------------------------------------------
struct JeandleTrapThrottleEntry {
  vmIntrinsics::ID      id;
  JeandleTrapReasonMask mask;
};

static constexpr JeandleTrapThrottleEntry kTrapThrottleTable[] = {
  { vmIntrinsics::_Preconditions_checkIndex,
    trap_reason_mask(Deoptimization::Reason_intrinsic) |
        trap_reason_mask(Deoptimization::Reason_range_check) },
  { vmIntrinsics::_Preconditions_checkLongIndex,
    trap_reason_mask(Deoptimization::Reason_intrinsic) |
        trap_reason_mask(Deoptimization::Reason_range_check) },
  { vmIntrinsics::_countPositives,
    trap_reason_mask(Deoptimization::Reason_intrinsic) },
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

JeandleTrapReasonMask JeandleIntrinsicRegistry::trap_throttle_mask(vmIntrinsics::ID id) {
  // Linear scan: the table is tiny (only deopt-capable intrinsics) and this runs
  // once per admission, so an O(n) scan is cheaper than building another lookup.
  for (const JeandleTrapThrottleEntry& entry : kTrapThrottleTable) {
    if (entry.id == id) {
      return entry.mask;
    }
  }
  return 0;
}
