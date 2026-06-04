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

#ifndef SHARE_JEANDLE_INTRINSIC_CALL_INFO_HPP
#define SHARE_JEANDLE_INTRINSIC_CALL_INFO_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/CallingConv.h"    // llvm::CallingConv::ID
#include "llvm/IR/DerivedTypes.h"   // llvm::FunctionCallee
#include "llvm/IR/Intrinsics.h"     // llvm::Intrinsic::ID
#include "llvm/IR/Module.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "jeandle/jeandleIntrinsicRegistry.hpp"
#include "memory/allocation.hpp"

// =============================================================================
// JeandleIntrinsicCallInfo — everything an intrinsic needs *only when its lowering emits
// a call site*.  Reached from JeandleIntrinsicDescriptor::call_info; the base
// descriptor stays minimal and LLVM-free.
//
// Rationale (the "call-only" split): a deopt bundle, an invoke exception edge,
// and GC-state visibility are properties of a call, not of a bare IR sequence.
// PureLLVM intrinsics (bitcast / fence / inline-asm / uncommon_trap) emit no
// such call site and carry no JeandleIntrinsicCallInfo.  LK_CALL intrinsics do:
// their control/memory facts and callee identity live here.  Hybrid bodies may
// emit call sites too, but they build JeandleCallSiteContract values inline rather
// than carrying one static CallInfo row.
//
// NOTE: GC barrier semantics do NOT live here.  The barrier semantic is reserved
// data on the base descriptor (JeandleIntrinsicDescriptor::barrier_kind) for a
// future late GC-barrier pass; it is lowering-independent (a plain load/store
// could carry the same kind), is not emitted today, and never drives lowering.
// Meanwhile the actual G1 barrier still lives inside the relevant JavaOp bodies.
// =============================================================================

// Control-flow facts.  Combined into JeandleCallSiteContract::control_flags with bitwise
// OR.  Unscoped so contract rows can write `CTRL_MAY_DEOPT | CTRL_...`.
enum JeandleControlFlag : uint8_t {
  CTRL_NONE                 = 0,
  // The lowering can transfer control to uncommon_trap / deopt.
  CTRL_MAY_DEOPT            = 1u << 0,
  // The call may throw a Java exception and needs invoke-style exception
  // continuation handling, not just deopt replay.
  CTRL_NEEDS_EXCEPTION_EDGE = 1u << 1,
};

// Memory-effect facts.  Combined into JeandleCallSiteContract::memory_flags with bitwise
// OR and translated into LLVM call-site memory attributes where safe.
enum JeandleMemoryFlag : uint16_t {
  MEM_NONE              = 0,
  // The call reads LLVM-visible memory.  Combined with MEM_WRITE for RMW.
  MEM_READ              = 1u << 0,
  // The call writes LLVM-visible memory.
  MEM_WRITE             = 1u << 1,
  // The call only constrains memory ordering (fence-like).  Mutually exclusive
  // with MEM_READ / MEM_WRITE.
  MEM_ORDERING_ONLY     = 1u << 2,
  // The lowered call must remain visible to GC-aware statepoint code.
  MEM_NEEDS_GC_STATE    = 1u << 3,
  // NOTE: GC barrier semantics are NOT modeled in these flags — a barrier is a
  // lowering-time decision emitted via a shared helper, not a memory-flag bit.
};

// Which kind of callee a data-driven LK_CALL intrinsic targets.  (A single llvm.*
// builtin is NOT a callee kind here — such intrinsics are PureLLVM, lowered via the
// LLVM op table (kLlvmOpTable), and carry no JeandleIntrinsicCallInfo.)
enum class JeandleIntrinsicCalleeKind : uint8_t {
  // No generic callee — a Hybrid body resolves and emits the call itself
  // (e.g. StringCoding.countPositives via resolve_count_positives).
  None,
  // A Jeandle JavaOp function in the module, named by java_op_name.
  JavaOp,
  // A HotSpot runtime stub / SharedRuntime routine, materialized via the
  // *_callee_fn resolvers.  (A libm intrinsic's llvm.* builtin is a separate
  // LK_LLVM candidate, not a fallback inside this kind.)
  RuntimeStub,
};

// Property-driven runtime-callee resolver.  One function per runtime routine that
// materializes the stub / SharedRuntime FunctionCallee in the given module.
// Storing these as data on the LK_CALL descriptor lets emit_simple_call_intrinsic
// resolve a callee generically — never switching on intrinsic id.  Hybrid bodies
// can still pass resolver functions directly when they need the same runtime path.
using JeandleRuntimeCalleeFn = llvm::FunctionCallee (*)(llvm::Module&);

// A resolved (materialized) runtime callee plus the IR-level facts a lowering needs
// to emit a call to it.  resolve_runtime_callee (and the inline resolver in a SIMD
// lower_* body) fills it; emit_callsite consumes it; annotate_call reads
// is_gc_leaf.  Shared by the lowering and IR-semantics layers.
struct JeandleIntrinsicEntrypoint {
  llvm::FunctionCallee  callee;
  llvm::CallingConv::ID calling_conv;
  bool                  is_gc_leaf;
};

// The call-site contract: the control + memory facts that decide HOW a single call
// site is emitted — the deopt operand bundle, the exception edge, the LLVM memory
// attribute, and the flag-based part of the gc-leaf decision.  It is shared by two
// producers: data-driven Call lowering holds it inside a static JeandleIntrinsicCallInfo,
// while a Hybrid body builds it on the fly at lowering time (a Hybrid may emit
// several call sites, each with its own contract, so one static contract cannot
// describe it).  emit_callsite consumes exactly this — never the whole descriptor.
struct JeandleCallSiteContract {
  uint8_t  control_flags;   // bitmask of JeandleControlFlag
  uint16_t memory_flags;    // bitmask of JeandleMemoryFlag

  bool may_deopt()            const { return (control_flags & CTRL_MAY_DEOPT) != 0; }
  bool needs_exception_edge() const { return (control_flags & CTRL_NEEDS_EXCEPTION_EDGE) != 0; }
  bool reads_memory()         const { return (memory_flags  & MEM_READ) != 0; }
  bool writes_memory()        const { return (memory_flags  & MEM_WRITE) != 0; }
  bool only_orders_memory()   const { return (memory_flags  & MEM_ORDERING_ONLY) != 0; }
  bool needs_gc_state()       const { return (memory_flags  & MEM_NEEDS_GC_STATE) != 0; }

  // A deopt bundle is required when the call can deopt, can safepoint (every
  // safepoint is a potential deopt point that must carry interpreter state), or
  // needs an exception edge that crosses Java EH.
  bool attach_deopt_bundle() const {
    return may_deopt() || needs_gc_state() || needs_exception_edge();
  }
  // The flag-based part of the gc-leaf decision: the call neither observes GC
  // state, deopts, nor unwinds, so it cannot reach a safepoint.  A managed/JavaOp
  // callee must declare MEM_NEEDS_GC_STATE (all current ones do), so it is
  // correctly excluded here without a callee_kind check; a runtime entry
  // independently known to be a leaf routine can force gc-leaf even when these
  // flags alone would not (see JeandleIntrinsicIRSemantics::annotate_call).
  bool gc_leaf_by_flags() const {
    return !needs_gc_state() && !may_deopt() && !needs_exception_edge();
  }
};

struct JeandleIntrinsicCallInfo {
  // ---- call-site contract (control + memory facts) ----
  JeandleCallSiteContract contract;

  // ---- callee identity (discriminated by callee_kind) ----
  // A RuntimeStub's runtime path exists when a resolver is non-null.  (Whether a
  // path is *available* in this VM — stub installed, CPU feature present — is
  // resolved at runtime by JeandleIntrinsicSupport::runtime_availability, keyed on the id.  A libm
  // intrinsic's llvm.* builtin is a separate LK_LLVM candidate, not a fallback
  // stored here.)
  JeandleIntrinsicCalleeKind      callee_kind;
  const char*            java_op_name;     // JavaOp
  JeandleRuntimeCalleeFn stub_callee_fn;   // RuntimeStub: StubRoutines_* (nullptr if none)
  JeandleRuntimeCalleeFn shared_callee_fn; // RuntimeStub: SharedRuntime_*

  // NOTE: no operand-stack shape here.  For a Call intrinsic the arg count/types
  // and the pushed result type are fully determined by the intercepted Java
  // method's signature (plus the receiver for instance methods), so
  // emit_simple_call_intrinsic derives them at lowering time from
  // _target->signature() via JeandleType::actual2computational — there is nothing
  // to encode per-intrinsic.  This makes the Call tables shape-agnostic: a
  // multi-arg runtime stub (crc32, AES, ...) needs no new columns.

  // ---- named accessors: flag predicates delegate to the contract ----
  bool may_deopt()             const { return contract.may_deopt(); }
  bool needs_exception_edge()  const { return contract.needs_exception_edge(); }
  bool reads_memory()          const { return contract.reads_memory(); }
  bool writes_memory()         const { return contract.writes_memory(); }
  bool only_orders_memory()    const { return contract.only_orders_memory(); }
  bool needs_gc_state()        const { return contract.needs_gc_state(); }
};

#endif // SHARE_JEANDLE_INTRINSIC_CALL_INFO_HPP
