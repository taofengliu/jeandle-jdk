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
//       id, lowering_kinds, call_info, barrier_kind.  (Trap throttling is a
//       separate id-keyed side-table, kTrapThrottleTable.)
//   JeandleIntrinsicCallInfo (callinfo.hpp) — everything needed only when the lowering
//       emits a call site: the call-site contract (control/memory facts) and the
//       callee identity.
//
// Most intrinsics are ONE LINE in a table macro below.  Each table macro is
// expanded twice (X-macro): once to define a `static constexpr JeandleIntrinsicCallInfo
// ci_<name>`, once to emit the base-descriptor row pointing at it.  Pick the
// table by *shape*:
//
//   JEANDLE_CALL_RUNTIME_STUB_TABLE — Call backed by a HotSpot runtime stub /
//       SharedRuntime routine; the stub / SharedRuntime resolvers are derived from
//       `name` by token paste.  `V(name)`.  (The libm family dsin..dexp is dual: it
//       is also in JEANDLE_PURE_TABLE / kLlvmOpTable as an LK_LLVM builtin.)
//   JEANDLE_CALL_JAVAOP_TABLE — Call delegating to a JavaOp.
//       `V(name, java_op, ctrl, mem, barrier)`.
//   JEANDLE_PURE_TABLE — PureLLVM (bare IR / inline-asm / uncommon_trap); no
//       call site, call_info == nullptr.  `V(name)`.
//   (Single-llvm.*-builtin intrinsics — dabs/fabs/dsqrt/floor/... — are also
//    PureLLVM, but their op is in the LLVM op table (kLlvmOpTable) in
//    jeandleIntrinsicLowering.cpp rather than a registry table; they need no
//    JeandleIntrinsicCallInfo.)
//
// Operand/result stack shapes are NOT in these tables — they come from the
// intercepted method's signature at lowering time.
//
// Intrinsics that do not fit a table (Hybrid bodies, trap-throttled PureLLVM) are
// written out explicitly right after the tables.  After adding a row (or an explicit
// entry), add the corresponding implementation in these locations:
//   - Support (jeandleIntrinsicSupport.cpp) — only if a runtime stub or a CPU-gated
//     builtin is advertised: add a `case` to probe_hotspot_stubs (runtime_availability)
//     or cpu_supports_llvm_builtin.  Both entry points are generic and id-keyed —
//     you add a case, never a new query function.
//   - Lowering (jeandleIntrinsicLowering.cpp) — Call needs no code; Hybrid /
//     PureLLVM add a `case` routed to a lower_* helper.
//   - JavaOp callees — define the body in template.ll / jeandleRuntimeDefinedJavaOps.cpp.
//   - A jtreg test under test/hotspot/jtreg/compiler/jeandle/.
// =============================================================================

static constexpr JeandleTrapReasonMask trap_reason_mask(Deoptimization::DeoptReason reason) {
  return JeandleTrapReasonMask(1u) << static_cast<uint>(reason);
}

// ---- One-line intrinsic tables (see the header comment for the column guide) ----

// Call backed by a HotSpot runtime stub / SharedRuntime routine.  The stub and
// SharedRuntime resolvers are derived from vm_name by token paste
// (StubRoutines_<vm_name>_callee / SharedRuntime_<vm_name>_callee).  Arg and result
// types come from the method signature, so this table is shape-agnostic — a future
// multi-arg stub (crc32, AES, ...) adds a row with no new columns.
//   vm_name — vmIntrinsics::_<vm_name>; also drives the resolver names
//
// The dsin..dexp libm family is dual-candidate: this is their *stub* candidate
// (LK_CALL).  Their llvm.* builtin candidate (LK_LLVM) lives in kLlvmOpTable +
// JEANDLE_PURE_TABLE; the registry OR-merges the two into LK_LLVM | LK_CALL and the
// priority traversal (plus the JeandleUseHotspotIntrinsics flag) picks between them.
#define JEANDLE_CALL_RUNTIME_STUB_TABLE(V) \
  V(dsin)   \
  V(dcos)   \
  V(dtan)   \
  V(dlog)   \
  V(dlog10) \
  V(dexp)

// Call delegating to a JavaOp.  Stack shape comes from the method signature.
//   Columns: vm_name, java_op_name, control_flags, memory_flags, barrier_kind
// (Trap throttling is not a column — see the trap-throttle side-table below.)
#define JEANDLE_CALL_JAVAOP_TABLE(V)                                                                     \
  V(getClass,                   "jeandle.get_class",          CTRL_NONE,                 MEM_READ | MEM_NEEDS_GC_STATE, None)             \
  V(Reference_get,              "jeandle.reference_get",      CTRL_NONE,                 MEM_READ | MEM_NEEDS_GC_STATE, WeakReferentLoad) \
  V(Reference_refersTo0,        "jeandle.reference_refers_to", CTRL_NONE,                MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead)  \
  V(PhantomReference_refersTo0, "jeandle.reference_refers_to", CTRL_NONE,                MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead)  \
  V(newArray,                   "jeandle.new_array",          CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE,          None)

// PureLLVM: bare LLVM IR / inline-asm / uncommon_trap / single llvm.* builtin;
// no call site, so call_info == nullptr.  Column: vm_name.  The actual lowering is
// driven by the LLVM op table (kLlvmOpTable) in jeandleIntrinsicLowering.cpp, where
// lower_llvm dispatches on each id's op; this table only supplies the base
// descriptor row.  (Trap throttling — which a PureLLVM body like Preconditions does
// need — is NOT here; it is a sparse id-keyed property kept in the trap-throttle
// side-table, see kTrapThrottleTable below.)
#define JEANDLE_PURE_TABLE(V) \
  /* single llvm.* builtin (lowered via kLlvmOpTable) */ \
  V(dabs)                     \
  V(fabs)                     \
  V(bitCount_i)               \
  V(dsqrt)                    \
  V(dsqrt_strict)             \
  V(floor)                    \
  V(ceil)                     \
  V(rint)                     \
  V(iabs)                     \
  V(labs)                     \
  V(bitCount_l)               \
  /* libm family: LK_LLVM (builtin) half of the dsin..dexp dual candidates; the stub half is in JEANDLE_CALL_RUNTIME_STUB_TABLE, OR-merged in the registry */ \
  V(dsin)                     \
  V(dcos)                     \
  V(dtan)                     \
  V(dlog)                     \
  V(dlog10)                   \
  V(dexp)                     \
  /* bare IR / inline-asm / uncommon_trap */ \
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
#define JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO(VM_NAME)                               \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                                   \
    { CTRL_NONE, MEM_NONE },                                                          \
    JeandleIntrinsicCalleeKind::RuntimeStub, nullptr,                                \
    &JeandleRuntimeRoutine::StubRoutines_##VM_NAME##_callee,                          \
    &JeandleRuntimeRoutine::SharedRuntime_##VM_NAME##_callee };
JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO)
#undef JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO

#define JEANDLE_DEFINE_JAVAOP_CALL_INFO(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS,         \
                                        MEMORY_FLAGS, BARRIER)                        \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                                   \
    { CONTROL_FLAGS, MEMORY_FLAGS },                                                  \
    JeandleIntrinsicCalleeKind::JavaOp, JAVA_OP_NAME,                                 \
    nullptr, nullptr };
JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_DEFINE_JAVAOP_CALL_INFO)
#undef JEANDLE_DEFINE_JAVAOP_CALL_INFO

// --- Hybrid: hand-written bodies that carry NO static JeandleIntrinsicCallInfo.
//     A Hybrid lowering (Math.pow, StringCoding.countPositives) resolves its own
//     callee and builds each call site's JeandleCallSiteContract on the fly at
//     lowering time — a Hybrid may emit several call sites, each with a different
//     contract — so a single static descriptor cannot describe it.  Their rows
//     below therefore use call_info == nullptr.

#ifdef ASSERT
static void validate_descriptor(const JeandleIntrinsicDescriptor& desc) {
  assert(desc.id != vmIntrinsics::_none && vmIntrinsics::is_valid_id(desc.id),
         "invalid Jeandle intrinsic id");
  assert(desc.lowering_kinds != LK_NONE, "intrinsic declares no lowering candidate");
  const JeandleIntrinsicCallInfo* ci = desc.call_info;
  // call_info is present iff LK_CALL is declared.  A Hybrid body carries no static
  // call_info — it builds its call-site contract on the fly at lowering time.
  assert(((desc.lowering_kinds & LK_CALL) != 0) == (ci != nullptr),
         "call_info must be present iff LK_CALL is declared");
  if (ci == nullptr) {
    return;
  }
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::JavaOp || ci->java_op_name != nullptr,
         "JavaOp callee requires a non-null java_op_name");
  assert(ci->java_op_name == nullptr || ci->java_op_name[0] != '\0',
         "empty JavaOp name string");
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::RuntimeStub ||
         ci->stub_callee_fn != nullptr || ci->shared_callee_fn != nullptr,
         "RuntimeStub needs a runtime resolver");
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
  // X-macro).  Field order: id, lowering_kinds, call_info, barrier_kind.  Hybrid
  // rows are listed last because that group grows as new hand-written intrinsics
  // are added.
  static constexpr JeandleIntrinsicDescriptor _intrinsic_table[] = {
    // ---- Call: LLVM builtin + runtime-stub (barrier-free) ----
#define JEANDLE_ROW_CALL(VM_NAME, ...) \
    { vmIntrinsics::_##VM_NAME, LK_CALL, &ci_##VM_NAME },
    JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_ROW_CALL)
#undef JEANDLE_ROW_CALL

    // ---- Call: JavaOp (barrier_kind carried from the table) ----
#define JEANDLE_ROW_JAVAOP(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS, MEMORY_FLAGS, BARRIER) \
    { vmIntrinsics::_##VM_NAME, LK_CALL, &ci_##VM_NAME, JeandleBarrierKind::BARRIER },
    JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_ROW_JAVAOP)
#undef JEANDLE_ROW_JAVAOP

    // ---- PureLLVM (no call site, call_info == nullptr) ----
#define JEANDLE_ROW_PURE(VM_NAME) \
    { vmIntrinsics::_##VM_NAME, LK_LLVM, nullptr },
    JEANDLE_PURE_TABLE(JEANDLE_ROW_PURE)
#undef JEANDLE_ROW_PURE

    // ---- Hybrid: hand-written bodies, no static call_info.  Each one's lower_*
    //      body is forced to exist by the token-paste dispatch generated from
    //      JEANDLE_HYBRID_TABLE in jeandleIntrinsicLowering.cpp.  Keep last; grows. ----
    { vmIntrinsics::_dpow,           LK_HYBRID, nullptr },
    { vmIntrinsics::_countPositives, LK_HYBRID, nullptr },
  };
};

// ---------------------------------------------------------------------------
// Trap-throttle side-table: id -> trap_throttle_mask.  Sparse (only intrinsics
// whose lowering can emit uncommon_trap appear here), id-keyed, and independent
// of lowering_kinds — any Call/Hybrid/PureLLVM intrinsic that deopts adds a row.
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

JeandleIntrinsicDescriptor
JeandleIntrinsicRegistry::_lookup[(int)vmIntrinsics::ID_LIMIT];

#ifdef ASSERT
bool JeandleIntrinsicRegistry::_initialized = false;
#endif

void JeandleIntrinsicRegistry::initialize() {
  // _lookup has static storage duration and is already zero-initialized — every slot
  // has lowering_kinds == LK_NONE, i.e. "absent" — before this function runs.

  for (const JeandleIntrinsicDescriptor* it = JeandleIntrinsicRegistryTable::table_begin();
       it != JeandleIntrinsicRegistryTable::table_end();
       ++it) {
    DEBUG_ONLY(validate_descriptor(*it);)
    JeandleIntrinsicDescriptor& slot = _lookup[vmIntrinsics::as_int(it->id)];
    if (slot.lowering_kinds == LK_NONE) {
      slot = *it;  // first table row for this id
    } else {
      // A multi-candidate intrinsic (e.g. dsin = LK_LLVM | LK_CALL) appears in more
      // than one table; OR the kind bits together and keep the call_info / barrier
      // from whichever row carries it.  The descriptor's kinds are thus *derived*
      // from table membership rather than a hand-written literal that could desync.
      slot.lowering_kinds |= it->lowering_kinds;
      if (it->call_info != nullptr) {
        assert(slot.call_info == nullptr || slot.call_info == it->call_info,
               "conflicting call_info for a merged intrinsic");
        slot.call_info = it->call_info;
      }
      if (it->barrier_kind != JeandleBarrierKind::None) {
        assert(slot.barrier_kind == JeandleBarrierKind::None,
               "conflicting barrier_kind for a merged intrinsic");
        slot.barrier_kind = it->barrier_kind;
      }
    }
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
  const JeandleIntrinsicDescriptor& slot = _lookup[vmIntrinsics::as_int(id)];
  return slot.lowering_kinds != LK_NONE ? &slot : nullptr;
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
