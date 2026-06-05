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

#ifndef SHARE_JEANDLE_INTRINSIC_TABLE_HPP
#define SHARE_JEANDLE_INTRINSIC_TABLE_HPP

// Shared intrinsic row source.  The tables are grouped by lowering mechanism,
// not by individual intrinsic.  Adding a lowering candidate should normally mean
// adding one row to one of these fixed tables; add a new table only when the
// framework gains a genuinely new lowering mechanism.
//
// Multi-candidate intrinsics appear in multiple tables.  The registry OR-merges
// their LK_* bits, and lowering tries LK_LLVM > LK_HYBRID > LK_CALL unless the
// diagnostic JeandleIntrinsicCandidate option masks the candidates.
//
// Developer guide:
//   - runtime stub call                -> JEANDLE_CALL_RUNTIME_STUB_TABLE
//   - JavaOp call                      -> JEANDLE_CALL_JAVAOP_TABLE
//   - no-call single llvm.* builtin    -> JEANDLE_LLVM_BUILTIN_TABLE
//   - no-call custom LLVM handler      -> JEANDLE_LLVM_CUSTOM_HANDLER_TABLE
//   - Hybrid lowering that may emit calls -> JEANDLE_HYBRID_HANDLER_TABLE
//
// A handler row uses `V(vm_name, handler_suffix)`.  The suffix is token-pasted
// to a lowering member named lower_<handler_suffix>(desc).  "Handler" here means
// lowering handler, not exception handler.  New handlers must be declared in
// JeandleIntrinsicLowering and defined in the shared or arch-specific lowering
// source file.
//
// Operand/result stack shape comes from the intercepted method signature.  Deopt
// throttling is not a row column: any lowering that can emit uncommon_trap must
// add an id-keyed row to kTrapThrottleTable.

// Call backed by a HotSpot runtime stub / SharedRuntime routine.  The stub and
// SharedRuntime resolver wrappers are derived from vm_name by token paste
// (StubRoutines_<vm_name>_callee / SharedRuntime_<vm_name>_callee).  Support
// must also report runtime availability for this id; missing availability makes
// the LK_CALL candidate decline rather than falling back here.
//   V(vm_name)
//
// The dsin..dexp libm family is dual-candidate: this table supplies the LK_CALL
// runtime candidate; JEANDLE_LLVM_BUILTIN_TABLE supplies the LK_LLVM builtin
// candidate.
#define JEANDLE_CALL_RUNTIME_STUB_TABLE(V) \
  V(dsin)   \
  V(dcos)   \
  V(dtan)   \
  V(dlog)   \
  V(dlog10) \
  V(dexp)

// Call delegating to a JavaOp.  The JavaOp symbol must exist in the template
// module, and its signature must match the intercepted method's receiver/args.
//   V(vm_name, java_op_name, control_flags, memory_flags, barrier_kind)
// barrier_kind is descriptor metadata for future barrier handling, not part of
// the static call_info contract.
#define JEANDLE_CALL_JAVAOP_TABLE(V) \
  V(getClass, "jeandle.get_class", \
    CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE, None) \
  V(Reference_get, "jeandle.reference_get", \
    CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE, WeakReferentLoad) \
  V(Reference_refersTo0, "jeandle.reference_refers_to", \
    CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead) \
  V(PhantomReference_refersTo0, "jeandle.reference_refers_to", \
    CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE, RawReferentRead) \
  V(newArray, "jeandle.new_array", \
    CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE, None)

// LK_LLVM builtin: lowered by emit_llvm_builtin to a single llvm.* intrinsic call,
// with operand/result types taken from the signature.  No call site, no static
// JeandleIntrinsicCallInfo.  This is the only data-driven LK_LLVM shape; everything
// else is a custom handler below.
//   V(vm_name, llvm_intrinsic_name)
#define JEANDLE_LLVM_BUILTIN_TABLE(V) \
  V(dabs,         fabs) \
  V(fabs,         fabs) \
  V(bitCount_i,   ctpop) \
  V(dsqrt,        sqrt) \
  V(dsqrt_strict, sqrt) \
  V(floor,        floor) \
  V(ceil,         ceil) \
  V(rint,         rint) \
  V(iabs,         abs) \
  V(labs,         abs) \
  V(bitCount_l,   ctpop) \
  V(dsin,         sin) \
  V(dcos,         cos) \
  V(dtan,         tan) \
  V(dlog,         log) \
  V(dlog10,       log10) \
  V(dexp,         exp)

// LK_LLVM custom handler: no semantic call site, no static call_info.  The handler
// owns bespoke no-call lowering (bitcast / fence / inline-asm sink / guard+trap /
// platform asm) that is not a single llvm.* builtin.  lower_llvm dispatches on the
// id to lower_<handler_suffix>(desc); if a handler needs emit_callsite(), use
// JEANDLE_HYBRID_HANDLER_TABLE instead.
//   V(vm_name, handler_suffix)
#define JEANDLE_LLVM_CUSTOM_HANDLER_TABLE(V) \
  V(floatToRawIntBits,            llvm_bitcast) \
  V(intBitsToFloat,               llvm_bitcast) \
  V(doubleToRawLongBits,          llvm_bitcast) \
  V(longBitsToDouble,             llvm_bitcast) \
  V(loadFence,                    llvm_fence) \
  V(storeFence,                   llvm_fence) \
  V(fullFence,                    llvm_fence) \
  V(blackhole,                    llvm_sink) \
  V(onSpinWait,                   spin_wait_hint) \
  V(Preconditions_checkIndex,     preconditions_check_index) \
  V(Preconditions_checkLongIndex, preconditions_check_index)

// LK_HYBRID handler: may emit one or more semantic call sites.  The handler
// resolves each callee and builds each JeandleCallSiteContract at lowering time,
// so it carries no static JeandleIntrinsicCallInfo.
//   V(vm_name, handler_suffix)
#define JEANDLE_HYBRID_HANDLER_TABLE(V) \
  V(dpow,           pow_hybrid) \
  V(countPositives, count_positives)

#endif // SHARE_JEANDLE_INTRINSIC_TABLE_HPP
