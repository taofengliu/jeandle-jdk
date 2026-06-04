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

#include "jeandle/jeandleIntrinsicCallInfo.hpp"
#include "memory/allocation.hpp"

class JeandleAbstractInterpreter;
class ciMethod;

class JeandleIntrinsicLowering : public StackObj {
  JeandleAbstractInterpreter* _interp;
  const ciMethod* _target;

  // LK_CALL — generic data-driven lowering: pop the args (shape derived from the
  // target method signature), call the callee, push the result.  No per-intrinsic
  // code; the callee + call-site facts are read off call_info.
  bool emit_simple_call_intrinsic(const JeandleIntrinsicDescriptor& desc);
  // Resolve a runtime-stub callee from the given resolvers (never a switch on the
  // intrinsic id).  Returns true iff an installed stub / SharedRuntime is found,
  // filling `entry`; false otherwise (the caller then falls back as it chooses —
  // NormalInvoke, or its own builtin).  Takes the callee identity explicitly so
  // hand-written bodies do not need call_info.
  bool resolve_runtime_callee(vmIntrinsics::ID id,
                              JeandleRuntimeCalleeFn stub_fn,
                              JeandleRuntimeCalleeFn shared_fn,
                              JeandleIntrinsicEntrypoint& entry);

  // LK_LLVM lowering (call_info == nullptr; bare IR / inline asm / traps): a single
  // skeleton dispatching on the LLVM op (see kLlvmOpTable in the .cpp), plus one
  // emit helper per data-driven op.  Operand/result types come from the signature.
  bool lower_llvm(const JeandleIntrinsicDescriptor& desc);
  bool emit_llvm_builtin(const JeandleIntrinsicDescriptor& desc,
                           llvm::Intrinsic::ID llvm_id);
  bool emit_llvm_bitcast(const JeandleIntrinsicDescriptor& desc);
  bool emit_llvm_fence(const JeandleIntrinsicDescriptor& desc);
  bool emit_llvm_sink(const JeandleIntrinsicDescriptor& desc);
  // LO_CUSTOM hand-written bodies: a guard+trap (Preconditions) and the
  // platform-specific spin-wait hint (implemented in
  // cpu/<arch>/jeandleIntrinsicLowering_<arch>.cpp).
  bool lower_preconditions_check_index(const JeandleIntrinsicDescriptor& desc);
  bool lower_spin_wait_hint(const JeandleIntrinsicDescriptor& desc);

  // Hybrid handlers: hand-written bodies that may wrap call sites in guards / fast
  // paths.  They carry no static call_info and build each call-site contract inline.
  bool lower_pow_hybrid(const JeandleIntrinsicDescriptor& desc);
  bool lower_count_positives(const JeandleIntrinsicDescriptor& desc);

  // Shared call-site skeleton for runtime stubs and JavaOps: builds the deopt
  // bundle, emits a call or an invoke (call path marked nounwind) based on
  // contract.needs_exception_edge(), then runs the common IR annotations.  The
  // call-site contract is the only call_info-derived input — a Hybrid body passes
  // one built on the fly.  entry is optional runtime-stub metadata (nullptr for
  // JavaOps).
  llvm::CallBase* emit_callsite(const JeandleIntrinsicDescriptor& desc,
                                llvm::FunctionCallee callee,
                                llvm::CallingConv::ID calling_conv,
                                llvm::ArrayRef<llvm::Value*> args,
                                const JeandleCallSiteContract& contract,
                                const JeandleIntrinsicEntrypoint* entry = nullptr);
  // JavaOp call site. Thin facade over emit_callsite resolving the JavaOp symbol
  // from call_info->java_op_name.
  llvm::CallBase* emit_java_op_call(const JeandleIntrinsicDescriptor& desc,
                                    llvm::ArrayRef<llvm::Value*> args);
  void attach_callee_return_klass_attr(llvm::CallBase* call) const;

 public:
  explicit JeandleIntrinsicLowering(JeandleAbstractInterpreter* interp)
    : _interp(interp), _target(nullptr) {}

  bool lower(const JeandleIntrinsicDescriptor& desc,
             const ciMethod* target);
};

#endif // SHARE_JEANDLE_INTRINSIC_LOWERING_HPP
