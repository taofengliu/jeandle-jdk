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

#ifndef SHARE_JEANDLE_INTRINSIC_LOWERING_HPP
#define SHARE_JEANDLE_INTRINSIC_LOWERING_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Instruction.h"
#include "llvm/IR/Instructions.h"

#include "jeandle/jeandleCallSiteAttr.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "memory/allocation.hpp"
#include "runtime/deoptimization.hpp"

class JeandleAbstractInterpreter;
class ciMethod;

using JeandleTrapReasonMask = uint32_t;
static_assert(Deoptimization::Reason_LIMIT <= 32,
              "JeandleTrapReasonMask must be widened");

class JeandleIntrinsicLowering : public StackObj {
 public:
  explicit JeandleIntrinsicLowering(JeandleAbstractInterpreter* interp);

  // Lower the given intrinsic. Returns true on success, false if the intrinsic
  // cannot be lowered (caller should fall back to normal invoke).
  bool lower(vmIntrinsics::ID id, const ciMethod* target);

  // Is this intrinsic ID one that Jeandle knows how to lower?
  static bool is_supported(vmIntrinsics::ID id);

  // Trap-throttle mask: deopt reasons that should throttle admission when
  // too many traps occurred at the invoke site. Returns 0 for intrinsics
  // that never deopt.
  static JeandleTrapReasonMask trap_throttle_mask(vmIntrinsics::ID id);

 private:
  JeandleAbstractInterpreter* _interp;
  const ciMethod* _target;

  // ========================================================================
  // Shared emit helpers
  // ========================================================================

  // Central call-site emission: builds deopt bundle, emits call or invoke,
  // applies GC-leaf and memory annotations.
  llvm::CallBase* emit_callsite(llvm::FunctionCallee callee,
                                llvm::CallingConv::ID cc,
                                llvm::ArrayRef<llvm::Value*> args,
                                const CallSiteAttributeMetadata& attrs,
                                bool is_gc_leaf_entry = false);

  // Emit a llvm.* builtin. Pops all Java args from the JVM stack (from signature),
  // appends extra_args, creates the intrinsic call, and pushes the result.
  bool emit_llvm_builtin(llvm::Intrinsic::ID llvm_id,
                          llvm::ArrayRef<llvm::Value*> extra_args = {});

  // ========================================================================
  // Pattern helpers — reusable lowering patterns shared by multiple intrinsics
  // ========================================================================

  // Dual-path libm (dsin, dcos, dtan, dlog, dlog10, dexp):
  //   JeandleUseHotspotIntrinsics=true  -> try stub -> try SharedRuntime -> llvm builtin
  //   JeandleUseHotspotIntrinsics=false -> llvm builtin only
  bool lower_dual_path_libm(llvm::Intrinsic::ID llvm_id,
                            const char* stub_name,
                            JeandleRuntimeCalleeFn stub_fn,
                            const char* shared_name,
                            JeandleRuntimeCalleeFn shared_fn);

  // JavaOp-based intrinsic: resolve the named JavaOp, pop args, call, push result.
  bool lower_java_op(const char* java_op_name,
                     const CallSiteAttributeMetadata& attrs);

  // ========================================================================
  // Per-intrinsic handlers
  // ========================================================================
  bool lower_llvm_bitcast();
  bool lower_llvm_fence(vmIntrinsics::ID id);
  bool lower_llvm_sink();
  bool lower_preconditions_check_index(vmIntrinsics::ID id);
  bool lower_spin_wait_hint();       // arch-specific
  bool lower_pow();
  bool lower_count_positives();
  bool lower_compare_unsigned(vmIntrinsics::ID id);

  // Attach JavaKlass/JavaKlassExact return-type attributes to a call site.
  void attach_callee_return_klass_attr(llvm::CallBase* call) const;
};

// =============================================================================
// Arch-specific CPU feature checks for intrinsics.
// Defined in cpu/<arch>/jeandleIntrinsicLowering_<arch>.cpp.
// Return true if the current CPU supports the given intrinsic on this
// architecture.
// =============================================================================
bool cpu_supports_rounding();   // floor/ceil/rint
bool cpu_supports_popcount();   // bitCount_i/bitCount_l
bool cpu_supports_spin_wait();  // onSpinWait

#endif // SHARE_JEANDLE_INTRINSIC_LOWERING_HPP
