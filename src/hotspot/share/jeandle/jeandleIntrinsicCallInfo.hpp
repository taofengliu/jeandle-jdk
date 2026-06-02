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
#include "llvm/IR/DerivedTypes.h"   // llvm::FunctionCallee
#include "llvm/IR/Intrinsics.h"     // llvm::Intrinsic::ID
#include "llvm/IR/Module.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "jeandle/jeandleIntrinsicRegistry.hpp"
#include "memory/allocation.hpp"

// =============================================================================
// JeandleCallInfo — everything an intrinsic needs *only when its lowering emits
// a call site*.  Reached from JeandleIntrinsicDescriptor::call_info; the base
// descriptor stays minimal and LLVM-free.
//
// Rationale (the "call-only" split): a deopt bundle, an invoke exception edge,
// and GC-state visibility are properties of a call, not of a bare IR sequence.
// PureLLVM intrinsics (bitcast / fence / inline-asm / uncommon_trap) emit no
// such call site and carry no JeandleCallInfo.  Call and Hybrid intrinsics do,
// so the control/memory/support facts, the callee identity, and the operand
// stack shape all live here.
//
// NOTE: GC barrier semantics do NOT live here.  The barrier semantic is reserved
// data on the base descriptor (JeandleIntrinsicDescriptor::barrier_kind) for a
// future late GC-barrier pass; it is lowering-independent (a plain load/store
// could carry the same kind), is not emitted today, and never drives lowering.
// Meanwhile the actual G1 barrier still lives inside the relevant JavaOp bodies.
// =============================================================================

// Control-flow facts.  Combined into JeandleCallInfo::control_flags with bitwise
// OR.  Unscoped so call-info rows can write `CTRL_MAY_DEOPT | CTRL_...`.
enum JeandleControlFlag : uint8_t {
  CTRL_NONE                 = 0,
  // The lowering can transfer control to uncommon_trap / deopt.
  CTRL_MAY_DEOPT            = 1u << 0,
  // The call may throw a Java exception and needs invoke-style exception
  // continuation handling, not just deopt replay.
  CTRL_NEEDS_EXCEPTION_EDGE = 1u << 1,
};

// Memory-effect facts.  Combined into JeandleCallInfo::memory_flags with bitwise
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

// What lowering paths a call-based descriptor *declares* it can take.  Combined
// into JeandleCallInfo::support_flags with bitwise OR.  Per-VM availability of
// those paths (stub installed, CPU feature present) is resolved at runtime by
// JeandleIntrinsicSupport.
enum JeandleSupportFlag : uint8_t {
  SUPPORT_NONE          = 0,
  // A HotSpot-generated stub or SharedRuntime fallback is an available impl.
  SUPPORT_HOTSPOT_STUB  = 1u << 0,
  // LLVM has a builtin or direct IR representation for this intrinsic.
  SUPPORT_LLVM_INTRIN   = 1u << 1,
};

// Which kind of callee a Call / Hybrid intrinsic targets.
enum class JeandleCalleeKind : uint8_t {
  // No generic callee — a Hybrid body resolves and emits the call itself
  // (e.g. StringCoding.countPositives via resolve_count_positives).
  None,
  // A Jeandle JavaOp function in the module, named by java_op_name.
  JavaOp,
  // A named llvm.* builtin identified by llvm_intrin_id.
  LLVMBuiltin,
  // A HotSpot runtime stub / SharedRuntime routine, materialized via the
  // *_callee_fn resolvers; falls back to the llvm_intrin_id builtin when the
  // runtime path is unavailable or not preferred.
  RuntimeStub,
};

// Property-driven runtime-callee resolver.  One function per runtime routine that
// materializes the stub / SharedRuntime FunctionCallee in the given module.
// Storing these as data on the descriptor lets emit_simple_call_intrinsic and the
// Hybrid bodies resolve a callee generically — never switching on intrinsic id.
using JeandleRuntimeCalleeFn = llvm::FunctionCallee (*)(llvm::Module&);

struct JeandleCallInfo {
  // ---- call-site semantics (moved out of the base descriptor) ----
  uint8_t  control_flags;   // bitmask of JeandleControlFlag
  uint16_t memory_flags;    // bitmask of JeandleMemoryFlag
  uint8_t  support_flags;   // bitmask of JeandleSupportFlag

  // ---- callee identity (discriminated by callee_kind) ----
  JeandleCalleeKind      callee_kind;
  const char*            java_op_name;     // JavaOp
  llvm::Intrinsic::ID    llvm_intrin_id;   // LLVMBuiltin; RuntimeStub builtin fallback
  JeandleRuntimeCalleeFn stub_callee_fn;   // RuntimeStub: StubRoutines_* (nullptr if none)
  JeandleRuntimeCalleeFn shared_callee_fn; // RuntimeStub: SharedRuntime_*

  // ---- operand-stack shape (Call only; Hybrid bodies pop/push themselves) ----
  // arg_types is in *call-argument* order: arg_types[0] is the first callee
  // parameter, arg_types[arg_count-1] is on top of the operand stack, so
  // emit_simple_call_intrinsic pops in reverse.
  BasicType arg_types[3];
  uint8_t   arg_count;
  BasicType result_type;    // T_VOID = no result pushed

  // ---- named accessors ----
  bool may_deopt()             const { return (control_flags & CTRL_MAY_DEOPT) != 0; }
  bool needs_exception_edge()  const { return (control_flags & CTRL_NEEDS_EXCEPTION_EDGE) != 0; }
  bool reads_memory()          const { return (memory_flags  & MEM_READ) != 0; }
  bool writes_memory()         const { return (memory_flags  & MEM_WRITE) != 0; }
  bool only_orders_memory()    const { return (memory_flags  & MEM_ORDERING_ONLY) != 0; }
  bool needs_gc_state()        const { return (memory_flags  & MEM_NEEDS_GC_STATE) != 0; }
  bool supports_hotspot_stub() const { return (support_flags & SUPPORT_HOTSPOT_STUB) != 0; }
  bool supports_llvm_intrin()  const { return (support_flags & SUPPORT_LLVM_INTRIN) != 0; }

  // ---- derived call-site contracts (folded from the old Policy::make_plan) ----
  // A deopt bundle is required when the call can deopt, can safepoint (every
  // safepoint is a potential deopt point that must carry interpreter state), or
  // needs an exception edge that crosses Java EH.
  bool attach_deopt_bundle() const {
    return may_deopt() || needs_gc_state() || needs_exception_edge();
  }
  // gc-leaf-function asserts the call site does not enter a safepoint;
  // RewriteStatepointsForGC reads it to skip statepoint rewriting.  JavaOps never
  // qualify (they may run arbitrary managed code).
  bool attach_gc_leaf() const {
    return !needs_gc_state() && !may_deopt() && !needs_exception_edge() &&
           callee_kind != JeandleCalleeKind::JavaOp;
  }
};

#endif // SHARE_JEANDLE_INTRINSIC_CALL_INFO_HPP
