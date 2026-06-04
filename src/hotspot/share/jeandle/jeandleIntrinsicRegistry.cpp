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
#include "jeandle/jeandleIntrinsicTable.hpp"

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
//   JeandleIntrinsicCallInfo (callinfo.hpp) — everything needed only when the
//       lowering emits a call site: the call-site contract (control/memory facts)
//       and the callee identity.
//
// The shared row sources live in jeandleIntrinsicTable.hpp.  Registry expands the
// tables into descriptors and CallInfo objects; lowering expands the same LLVM /
// Hybrid tables into kLlvmOpTable entries and custom-handler dispatch.  For
// Hybrid / custom LK_LLVM rows, the handler suffix is token-pasted into
// lower_<handler_suffix>, so a missing implementation fails at compile/link time
// instead of silently leaving registry and dispatch out of sync.
//
// Pick the table by mechanism: CALL_RUNTIME_STUB, CALL_JAVAOP, LLVM_INLINE_OP,
// LLVM_CUSTOM_HANDLER, or HYBRID_HANDLER.  Add a new table only when the
// framework gains a new lowering mechanism, not for each new intrinsic.
//
// Operand/result stack shapes are NOT in these tables — they come from the
// intercepted method's signature at lowering time.  Trap throttling is also not a
// row column; deopt-capable intrinsics add an id-keyed row to kTrapThrottleTable.
//
// After adding a row, wire the remaining pieces only where needed:
//   - Support (jeandleIntrinsicSupport.cpp) — runtime availability or CPU gating.
//   - JavaOp body — template.ll / jeandleRuntimeDefinedJavaOps.cpp for JavaOp rows.
//   - Lowering helper — only for new handler suffixes, or for a genuinely new
//     LLVM inline-op category.
//   - A jtreg test under test/hotspot/jtreg/compiler/jeandle/.
// =============================================================================

static constexpr JeandleTrapReasonMask trap_reason_mask(Deoptimization::DeoptReason reason) {
  return JeandleTrapReasonMask(1u) << static_cast<uint>(reason);
}

// ---- Pass 1: define a JeandleIntrinsicCallInfo per Call/JavaOp row. ----
#define JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO(VM_NAME)                               \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                         \
    { CTRL_NONE, MEM_NONE },                                                         \
    JeandleIntrinsicCalleeKind::RuntimeStub, nullptr,                                \
    &JeandleRuntimeRoutine::StubRoutines_##VM_NAME##_callee,                         \
    &JeandleRuntimeRoutine::SharedRuntime_##VM_NAME##_callee };
JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO)
#undef JEANDLE_DEFINE_RUNTIME_STUB_CALL_INFO

#define JEANDLE_DEFINE_JAVAOP_CALL_INFO(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS,        \
                                        MEMORY_FLAGS, BARRIER)                       \
  static constexpr JeandleIntrinsicCallInfo ci_##VM_NAME = {                         \
    { CONTROL_FLAGS, MEMORY_FLAGS },                                                 \
    JeandleIntrinsicCalleeKind::JavaOp, JAVA_OP_NAME,                                \
    nullptr, nullptr };
JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_DEFINE_JAVAOP_CALL_INFO)
#undef JEANDLE_DEFINE_JAVAOP_CALL_INFO

// --- Hybrid handlers carry NO static JeandleIntrinsicCallInfo.
//     A Hybrid lowering (Math.pow, StringCoding.countPositives) resolves its own
//     callee and builds each call site's JeandleCallSiteContract on the fly at
//     lowering time — a Hybrid may emit several call sites, each with a different
//     contract — so a single static descriptor cannot describe it.  Their shared
//     table rows therefore use call_info == nullptr.

#ifdef ASSERT
static void validate_descriptor(const JeandleIntrinsicDescriptor& desc) {
  assert(desc.id != vmIntrinsics::_none && vmIntrinsics::is_valid_id(desc.id),
         "invalid Jeandle intrinsic id");
  assert(desc.lowering_kinds != LK_NONE, "intrinsic declares no lowering candidate");
  const JeandleIntrinsicCallInfo* ci = desc.call_info;
  // call_info is present iff LK_CALL is declared.  A Hybrid handler carries no
  // static call_info — it builds its call-site contract on the fly.
  assert(((desc.lowering_kinds & LK_CALL) != 0) == (ci != nullptr),
         "call_info must be present iff LK_CALL is declared");
  if (ci == nullptr) {
    return;
  }
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::JavaOp || ci->java_op_name != nullptr,
         "JavaOp callee requires a non-null java_op_name");
  assert(ci->callee_kind != JeandleIntrinsicCalleeKind::JavaOp ||
         !ci->contract.gc_leaf_by_flags(),
         "JavaOp call sites must remain GC-visible/non-leaf");
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
  // Base descriptor rows, generated from jeandleIntrinsicTable.hpp.  Field order:
  // id, lowering_kinds, call_info, barrier_kind.  Multi-candidate ids appear once
  // per candidate and are OR-merged by initialize().
  static constexpr JeandleIntrinsicDescriptor _intrinsic_table[] = {
    // ---- Call: runtime stub / SharedRuntime (barrier-free) ----
#define JEANDLE_ROW_CALL(VM_NAME) \
    { vmIntrinsics::_##VM_NAME, LK_CALL, &ci_##VM_NAME },
    JEANDLE_CALL_RUNTIME_STUB_TABLE(JEANDLE_ROW_CALL)
#undef JEANDLE_ROW_CALL

    // ---- Call: JavaOp (barrier_kind carried from the table) ----
#define JEANDLE_ROW_JAVAOP(VM_NAME, JAVA_OP_NAME, CONTROL_FLAGS, MEMORY_FLAGS, BARRIER) \
    { vmIntrinsics::_##VM_NAME, LK_CALL, &ci_##VM_NAME, JeandleBarrierKind::BARRIER },
    JEANDLE_CALL_JAVAOP_TABLE(JEANDLE_ROW_JAVAOP)
#undef JEANDLE_ROW_JAVAOP

    // ---- LK_LLVM: inline-op rows ----
#define JEANDLE_ROW_LLVM_OP(VM_NAME, OP, LLVM_NAME) \
    { vmIntrinsics::_##VM_NAME, LK_LLVM, nullptr },
    JEANDLE_LLVM_INLINE_OP_TABLE(JEANDLE_ROW_LLVM_OP)
#undef JEANDLE_ROW_LLVM_OP

    // ---- LK_LLVM: custom handler rows ----
#define JEANDLE_ROW_LLVM_CUSTOM_HANDLER(VM_NAME, HANDLER_SUFFIX) \
    { vmIntrinsics::_##VM_NAME, LK_LLVM, nullptr },
    JEANDLE_LLVM_CUSTOM_HANDLER_TABLE(JEANDLE_ROW_LLVM_CUSTOM_HANDLER)
#undef JEANDLE_ROW_LLVM_CUSTOM_HANDLER

    // ---- LK_HYBRID: handler rows ----
#define JEANDLE_ROW_HYBRID(VM_NAME, HANDLER_SUFFIX) \
    { vmIntrinsics::_##VM_NAME, LK_HYBRID, nullptr },
    JEANDLE_HYBRID_HANDLER_TABLE(JEANDLE_ROW_HYBRID)
#undef JEANDLE_ROW_HYBRID
  };
};

// ---------------------------------------------------------------------------
// Trap-throttle side-table: id -> trap_throttle_mask.  Sparse (only intrinsics
// whose lowering can emit uncommon_trap appear here), id-keyed, and independent
// of lowering_kinds — any LK_CALL/LK_HYBRID/LK_LLVM intrinsic that deopts adds
// a row.
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
      // from table membership rather than a handwritten literal that could desync.
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
  for (int i = 0; i < (int)vmIntrinsics::ID_LIMIT; i++) {
    if (_lookup[i].lowering_kinds != LK_NONE) {
      validate_descriptor(_lookup[i]);
    }
  }
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
