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

#ifndef SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP
#define SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "memory/allocation.hpp"
#include "runtime/deoptimization.hpp"

class ciMethod;

// Defined in jeandleIntrinsicCallInfo.hpp.  That header pulls in LLVM types
// (Intrinsic::ID, FunctionCallee), so it is kept out of this descriptor header:
// the base descriptor stays LLVM-free and only holds a pointer to the call info.
struct JeandleCallInfo;

// Coarse lowering family — selects which lowering routine handles the intrinsic:
//
//   PureLLVM — bare LLVM IR / inline-asm / uncommon_trap.  Emits no semantic
//              call site, so it carries no JeandleCallInfo (call_info == nullptr).
//   Hybrid   — a hand-written lowering that wraps a call site in guards or
//              fast paths (Math.pow, StringCoding.countPositives).
//   Call     — fixed shape "pop args -> call the callee once -> push result",
//              handled generically by emit_simple_call_intrinsic.
//
// Hybrid and Call both emit a call site and therefore always carry a
// JeandleCallInfo (call_info != nullptr).
enum class JeandleLoweringKind : uint8_t {
  PureLLVM,
  Hybrid,
  Call
};

// GC barrier semantic of an intrinsic.  This is *annotation only*: it never
// drives lowering branch selection.  It is reserved data for a future late
// GC-barrier LLVM pass (analogous to the array GC barrier late insertion) that
// would insert the collector-specific barrier — e.g. the G1 SATB pre-barrier for
// a weak referent load — after optimization and before JavaOperationLower(1)
// inlines the JavaOp body.  How the kind is threaded to LLVM (named metadata on
// the call site? an operand bundle? a marker intrinsic?) is NOT decided yet, so
// nothing is emitted from it today (validate_descriptor only checks it for
// consistency); meanwhile the G1 pre-barrier stays inside the JavaOp body for
// correctness.  It is lowering-independent (a future inlined load/store could
// carry the same kind), so it lives on the base descriptor, not in
// JeandleCallInfo.  See jeandle-docs/intrinsics/pending-barrier-semantic-stability.md.
// Barriers are mutually exclusive, so a scoped enum models them better than a bitmask.
enum class JeandleBarrierKind : uint8_t {
  None,
  WeakReferentLoad,  // weak/soft referent load needing a keep-alive (SATB) barrier
  RawReferentRead,   // raw referent identity read, no barrier (suppression marker)
  CardMarkPost,      // post-write card-table mark
  VolatileLoad,      // volatile load acquire
  VolatileStore,     // volatile store release
};

using JeandleTrapReasonMask = uint32_t;
static_assert(Deoptimization::Reason_LIMIT <= 32,
              "JeandleTrapReasonMask must be widened");

// Base descriptor: one row per intrinsic Jeandle can lower.  It holds only the
// admission-time facts (identity, lowering family, trap throttle).  Everything
// tied to emitting a call site — control/memory semantics, callee identity and
// operand-stack shape — lives in JeandleCallInfo, reached through call_info.
//
// call_info is nullptr for pure-IR PureLLVM intrinsics and non-null for every
// Call / Hybrid intrinsic.
struct JeandleIntrinsicDescriptor {
  // VM intrinsic ID being described.  This is also the O(1) lookup-table key.
  vmIntrinsics::ID       id;
  // Coarse lowering family; see JeandleLoweringKind.
  JeandleLoweringKind    lowering_kind;
  // Call-site semantics + callee + stack shape.  nullptr iff lowering_kind is
  // PureLLVM.
  const JeandleCallInfo* call_info;
  // GC barrier semantic source: reserved data for a future late GC-barrier pass.
  // Not emitted anywhere today and never drives lowering.  Defaulted so rows that
  // omit it read as JeandleBarrierKind::None.
  JeandleBarrierKind     barrier_kind = JeandleBarrierKind::None;

  bool has_call_info() const { return call_info != nullptr; }
  bool has_barrier()   const { return barrier_kind != JeandleBarrierKind::None; }
};

class JeandleIntrinsicRegistry : public AllStatic {
 private:
  static const JeandleIntrinsicDescriptor* _lookup[(int)vmIntrinsics::ID_LIMIT];
#ifdef ASSERT
  static bool _initialized;
#endif

 public:
  static void initialize();
  static const JeandleIntrinsicDescriptor* lookup(vmIntrinsics::ID id);
  static const JeandleIntrinsicDescriptor* lookup(const ciMethod* method);

  // Trap-throttle mask for an intrinsic: deopt reasons that throttle admission
  // when too many traps occurred at the invoke site (read before too_many_traps
  // in JeandleAbstractInterpreter::try_lower_intrinsic).  This is a sparse,
  // id-keyed property — only a few intrinsics deopt — so it lives in a small
  // side-table rather than on every descriptor.  Returns 0 (no throttling) for
  // any id not in the table.  Independent of lowering_kind: any kind may throttle.
  static JeandleTrapReasonMask trap_throttle_mask(vmIntrinsics::ID id);
};

#endif // SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP
