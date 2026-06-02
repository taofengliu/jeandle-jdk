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

#include "jeandle/jeandleIntrinsicLowering.hpp"

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/Analysis/ConstantFolding.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/InlineAsm.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicCallInfo.hpp"
#include "jeandle/jeandleIntrinsicIRSemantics.hpp"
#include "jeandle/jeandleIntrinsicRegistry.hpp"
#include "jeandle/jeandleIntrinsicEntrypoints.hpp"
#include "jeandle/jeandleIntrinsicSupport.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "oops/arrayOop.hpp"
#include "runtime/stubRoutines.hpp"

static bool is_double_constant(llvm::Value* value, double expected,
                               const llvm::DataLayout& data_layout) {
  llvm::Constant* constant = llvm::dyn_cast<llvm::Constant>(value);
  if (constant == nullptr) {
    if (llvm::Instruction* inst = llvm::dyn_cast<llvm::Instruction>(value)) {
      constant = llvm::ConstantFoldInstruction(inst, data_layout);
    }
  }
  if (constant == nullptr) {
    return false;
  }
  constant = llvm::ConstantFoldConstant(constant, data_layout);
  llvm::ConstantFP* fp_constant = llvm::dyn_cast<llvm::ConstantFP>(constant);
  if (fp_constant == nullptr) {
    return false;
  }
  llvm::APFloat expected_value(expected);
  return fp_constant->getValueAPF().bitwiseIsEqual(expected_value);
}

void JeandleIntrinsicLowering::annotate_generated_instruction(llvm::Instruction& inst,
                                                              const JeandleIntrinsicDescriptor& desc,
                                                              const JeandleIntrinsicEntrypoint* entry) const {
  JeandleIntrinsicIRSemantics::annotate_instruction(inst, desc, entry);
}

// Mirror PR #430's call-site type-info attachment for object-returning intrinsics:
// the regular invoke() path runs this via attach_java_klass_ret_attr, but intrinsic
// dispatch returns from invoke() before that point.  Centralizing here keeps every
// CallBase the emit helpers produce on the same JavaKlass / JavaKlassExact contract.
void JeandleIntrinsicLowering::attach_callee_return_klass_attr(llvm::CallBase* call) const {
  if (_target == nullptr) {
    return;
  }
  attach_java_klass_ret_attr(call,
                             _target->signature()->return_type(),
                             *_interp->_context);
}

llvm::CallBase* JeandleIntrinsicLowering::emit_callsite(const JeandleIntrinsicDescriptor& desc,
                                                        llvm::FunctionCallee callee,
                                                        llvm::CallingConv::ID calling_conv,
                                                        llvm::ArrayRef<llvm::Value*> args,
                                                        const JeandleIntrinsicEntrypoint* entry) {
  const JeandleCallInfo* ci = desc.call_info;
  assert(ci != nullptr, "emit_callsite requires call_info (Call/Hybrid only)");
  llvm::SmallVector<llvm::OperandBundleDef, 1> bundles =
    JeandleIntrinsicIRSemantics::build_operand_bundles(_interp, ci->attach_deopt_bundle());
  llvm::CallBase* site;
  if (ci->needs_exception_edge()) {
    site = _interp->create_call_ex(callee, args, calling_conv, bundles);
  } else {
    // Plain call: the intrinsic raises no Java exception.  Mark nounwind so LLVM
    // does not conservatively treat it as a potential unwind point.
    site = _interp->create_call(callee, args, calling_conv, bundles);
    site->setDoesNotThrow();
    JeandleIntrinsicIRSemantics::apply_memory_attr(site, *ci);
  }
  annotate_generated_instruction(*site, desc, entry);
  attach_callee_return_klass_attr(site);
  return site;
}

llvm::CallBase* JeandleIntrinsicLowering::emit_runtime_call(const JeandleIntrinsicDescriptor& desc,
                                                            const JeandleIntrinsicEntrypoint& entry,
                                                            llvm::ArrayRef<llvm::Value*> args) {
  return emit_callsite(desc, entry.callee, entry.calling_conv, args, &entry);
}

llvm::CallBase* JeandleIntrinsicLowering::emit_java_op_call(const JeandleIntrinsicDescriptor& desc,
                                                            llvm::ArrayRef<llvm::Value*> args) {
  const JeandleCallInfo* ci = desc.call_info;
  assert(ci != nullptr && ci->java_op_name != nullptr, "JavaOp lowering requires a JavaOp symbol");
  llvm::Function* java_op = _interp->_module.getFunction(ci->java_op_name);
  assert(java_op != nullptr, "invalid JavaOp");
  // The JavaOp body is inlined later by jeandle-llvm's JavaOperationLower, which
  // matches on the callee's "lower-phase" attribute — the call site itself needs
  // no marker attribute.
  return emit_callsite(desc, java_op, llvm::CallingConv::Hotspot_JIT, args);
}

// Property-driven runtime-stub resolution: pick the HotSpot stub / SharedRuntime
// routine from call_info's resolver function pointers, or signal a builtin
// fallback.  Never switches on the intrinsic id.
bool JeandleIntrinsicLowering::resolve_runtime_callee(const JeandleIntrinsicDescriptor& desc,
                                                      JeandleIntrinsicEntrypoint& entry,
                                                      bool& has_entry) {
  const JeandleCallInfo* ci = desc.call_info;
  has_entry = false;
  JeandleIntrinsicCapabilities caps = JeandleIntrinsicSupport::query(desc);
  if (caps.hotspot_preferred && caps.any_runtime()) {
    JeandleRuntimeCalleeFn fn = nullptr;
    if (caps.has_hotspot_stub && ci->stub_callee_fn != nullptr) {
      fn = ci->stub_callee_fn;
    } else if (caps.has_shared_runtime && ci->shared_callee_fn != nullptr) {
      fn = ci->shared_callee_fn;
    }
    if (fn != nullptr) {
      entry.callee = fn(_interp->_module);
      entry.calling_conv = llvm::CallingConv::C;
      entry.is_gc_leaf = true;
      has_entry = true;
      return true;
    }
  }
  // Builtin fallback: caller emits CreateIntrinsic from ci->llvm_intrin_id.
  if (caps.has_llvm_builtin) {
    return true;
  }
  return false;
}

bool JeandleIntrinsicLowering::emit_simple_call_intrinsic(const JeandleIntrinsicDescriptor& desc) {
  const JeandleCallInfo* ci = desc.call_info;
  assert(ci != nullptr, "Call lowering requires call_info");
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // Resolve the callee form before popping args, so a CPU-feature miss can
  // decline cleanly without disturbing the operand stack.
  bool is_java_op = false;
  bool use_builtin = false;                       // emit via CreateIntrinsic(llvm_intrin_id)
  JeandleIntrinsicEntrypoint entry;
  bool has_entry = false;                          // runtime stub / SharedRuntime

  switch (ci->callee_kind) {
    case JeandleCalleeKind::JavaOp:
      is_java_op = true;
      break;
    case JeandleCalleeKind::LLVMBuiltin:
      if (!JeandleIntrinsicSupport::query(desc).has_llvm_builtin) {
        return false;  // e.g. floor/ceil/rint without SSE4.1 -> NormalInvoke fallback
      }
      use_builtin = true;
      break;
    case JeandleCalleeKind::RuntimeStub:
      if (!resolve_runtime_callee(desc, entry, has_entry)) {
        return false;
      }
      use_builtin = !has_entry;
      break;
    case JeandleCalleeKind::None:
      return false;  // a Call descriptor must name a generic callee
  }

  // Pop args in reverse: arg_types[arg_count-1] is on top of the operand stack.
  llvm::SmallVector<llvm::Value*, 3> args;
  args.resize(ci->arg_count);
  for (int i = (int)ci->arg_count - 1; i >= 0; --i) {
    args[i] = _interp->_jvm->pop(ci->arg_types[i]);
  }

  llvm::Value* result = nullptr;
  if (is_java_op) {
    result = emit_java_op_call(desc, args);
  } else if (use_builtin) {
    llvm::CallInst* call = builder.CreateIntrinsic(
        JeandleType::java2llvm(ci->arg_types[0], ctx), ci->llvm_intrin_id, args);
    annotate_generated_instruction(*call, desc);
    result = call;
  } else {
    result = emit_runtime_call(desc, entry, args);
  }

  if (ci->result_type != T_VOID) {
    _interp->_jvm->push(ci->result_type, result);
  }
  return true;
}

bool JeandleIntrinsicLowering::lower(const JeandleIntrinsicDescriptor& desc,
                                     const ciMethod* target) {
  _target = target;
  // Dispatch on the lowering family.  Call is fully data-driven; Hybrid and
  // PureLLVM dispatch on the precise id to a hand-written leaf handler.
  switch (desc.lowering_kind) {
    case JeandleLoweringKind::Call:
      return emit_simple_call_intrinsic(desc);

    case JeandleLoweringKind::Hybrid:
      switch (desc.id) {
        case vmIntrinsics::_dpow:           return lower_pow_hybrid(desc);
        case vmIntrinsics::_countPositives: return lower_count_positives(desc);
        default:                            return false;
      }

    case JeandleLoweringKind::PureLLVM:
      switch (desc.id) {
        case vmIntrinsics::_iabs:
        case vmIntrinsics::_labs:
        case vmIntrinsics::_bitCount_l:
          return lower_pure_math(desc);

        case vmIntrinsics::_floatToRawIntBits:
        case vmIntrinsics::_intBitsToFloat:
        case vmIntrinsics::_doubleToRawLongBits:
        case vmIntrinsics::_longBitsToDouble:
          return lower_type_coercion(desc);

        case vmIntrinsics::_loadFence:
        case vmIntrinsics::_storeFence:
        case vmIntrinsics::_fullFence:
          return lower_barrier_semantic(desc);

        case vmIntrinsics::_onSpinWait:
          return lower_spin_wait_hint(desc);

        case vmIntrinsics::_Preconditions_checkIndex:
        case vmIntrinsics::_Preconditions_checkLongIndex:
          return lower_preconditions_check_index(desc);

        case vmIntrinsics::_blackhole:
          return lower_blackhole(desc);

        default:
          return false;
      }
  }
  return false;
}

// Spec rows for lower_pure_math: PureLLVM math intrinsics that deviate from the
// plain Call shape — llvm.abs needs a trailing i1 poison flag, and Long.bitCount
// truncates an i64 ctpop result to i32.  (The plain shapes — dabs/fabs/dsqrt/
// bitCount_i/floor/ceil/rint — are JeandleLoweringKind::Call, handled generically
// by emit_simple_call_intrinsic.)
struct PureMathSpec {
  vmIntrinsics::ID    vm_id;
  llvm::Intrinsic::ID llvm_id;
  BasicType           operand_type;
  BasicType           result_type;
  bool                needs_poison_flag;  // llvm.abs requires a trailing i1
};

static constexpr PureMathSpec kPureMathTable[] = {
  { vmIntrinsics::_iabs,       llvm::Intrinsic::abs,   T_INT,  T_INT, true  },
  { vmIntrinsics::_labs,       llvm::Intrinsic::abs,   T_LONG, T_LONG, true  },
  { vmIntrinsics::_bitCount_l, llvm::Intrinsic::ctpop, T_LONG, T_INT,  false },
};

bool JeandleIntrinsicLowering::lower_pure_math(const JeandleIntrinsicDescriptor& desc) {
  for (const PureMathSpec& spec : kPureMathTable) {
    if (spec.vm_id != desc.id) continue;
    llvm::LLVMContext& ctx = *_interp->_context;
    llvm::IRBuilder<>& builder = _interp->_ir_builder;

    llvm::SmallVector<llvm::Value*, 2> args;
    args.push_back(_interp->_jvm->pop(spec.operand_type));
    if (spec.needs_poison_flag) {
      args.push_back(builder.getInt1(false));
    }

    llvm::CallInst* call = builder.CreateIntrinsic(
        JeandleType::java2llvm(spec.operand_type, ctx), spec.llvm_id, args);
    annotate_generated_instruction(*call, desc);

    llvm::Value* result = call;
    if (spec.result_type != spec.operand_type) {
      // e.g. Long.bitCount(long): ctpop gives i64, push as i32.
      result = builder.CreateTrunc(call, JeandleType::java2llvm(spec.result_type, ctx));
    }
    _interp->_jvm->push(spec.result_type, result);
    return true;
  }
  return false;
}

// Spec rows for lower_type_coercion: every Float/Int and Double/Long Raw-bits
// intrinsic is a single bitcast between two scalar types.
struct TypeCoercionSpec {
  vmIntrinsics::ID vm_id;
  BasicType        src_type;
  BasicType        dst_type;
};

static constexpr TypeCoercionSpec kTypeCoercionTable[] = {
  { vmIntrinsics::_floatToRawIntBits,   T_FLOAT,  T_INT    },
  { vmIntrinsics::_intBitsToFloat,      T_INT,    T_FLOAT  },
  { vmIntrinsics::_doubleToRawLongBits, T_DOUBLE, T_LONG   },
  { vmIntrinsics::_longBitsToDouble,    T_LONG,   T_DOUBLE },
};

bool JeandleIntrinsicLowering::lower_type_coercion(const JeandleIntrinsicDescriptor& desc) {
  for (const TypeCoercionSpec& spec : kTypeCoercionTable) {
    if (spec.vm_id != desc.id) continue;
    llvm::LLVMContext& ctx = *_interp->_context;
    llvm::IRBuilder<>& builder = _interp->_ir_builder;

    llvm::Value* src = _interp->_jvm->pop(spec.src_type);
    llvm::Value* cast = builder.CreateBitCast(src, JeandleType::java2llvm(spec.dst_type, ctx));
    // CreateBitCast may constant-fold to a llvm::Constant; only annotate an
    // actual instruction.
    if (llvm::Instruction* inst = llvm::dyn_cast<llvm::Instruction>(cast)) {
      annotate_generated_instruction(*inst, desc);
    }
    _interp->_jvm->push(spec.dst_type, cast);
    return true;
  }
  return false;
}

// Math.pow(base, exp): Hybrid — IR fast paths for the common constant exponents,
// otherwise a runtime/builtin pow call resolved property-driven from call_info.
bool JeandleIntrinsicLowering::lower_pow_hybrid(const JeandleIntrinsicDescriptor& desc) {
  const JeandleCallInfo* ci = desc.call_info;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Type* ret_ty = JeandleType::java2llvm(T_DOUBLE, ctx);

  llvm::Value* exp = _interp->_jvm->dpop();
  llvm::Value* base = _interp->_jvm->dpop();

  // Constant fast path: pow(x, 2.0) => x * x.
  if (is_double_constant(exp, 2.0, _interp->_module.getDataLayout())) {
    llvm::Value* fast = builder.CreateFMul(base, base);
    if (llvm::Instruction* inst = llvm::dyn_cast<llvm::Instruction>(fast)) {
      annotate_generated_instruction(*inst, desc);
    }
    _interp->_jvm->dpush(fast);
    return true;
  }

  // Resolve the slow / general pow callee once (runtime stub / SharedRuntime, or
  // the llvm.pow builtin fallback).
  JeandleIntrinsicEntrypoint entry;
  bool has_entry = false;
  if (!resolve_runtime_callee(desc, entry, has_entry)) {
    return false;
  }

  // Emit a full pow(base, exp) call via the resolved path.
  auto emit_slow = [&]() -> llvm::Value* {
    if (has_entry) {
      return emit_runtime_call(desc, entry, {base, exp});
    }
    llvm::Function* pow_fn = llvm::Intrinsic::getOrInsertDeclaration(
        &_interp->_module, ci->llvm_intrin_id, {ret_ty});
    llvm::CallInst* call = builder.CreateCall(pow_fn, {base, exp});
    annotate_generated_instruction(*call, desc);
    return call;
  };

  // Constant fast path: pow(x, 0.5) => x > 0.0 ? llvm.sqrt(x) : pow(x, 0.5).
  if (is_double_constant(exp, 0.5, _interp->_module.getDataLayout())) {
    llvm::Value* zero = llvm::ConstantFP::get(ret_ty, 0.0);
    llvm::BasicBlock* fast_block = llvm::BasicBlock::Create(ctx, "pow_0dot5_fast", _interp->_llvm_func);
    llvm::BasicBlock* slow_block = llvm::BasicBlock::Create(ctx, "pow_0dot5_slow", _interp->_llvm_func);
    llvm::BasicBlock* merge_block = llvm::BasicBlock::Create(ctx, "pow_0dot5_merge", _interp->_llvm_func);

    llvm::Value* base_gt_zero = builder.CreateFCmpOGT(base, zero, "pow.base_gt_zero");
    builder.CreateCondBr(base_gt_zero, fast_block, slow_block);

    builder.SetInsertPoint(fast_block);
    llvm::CallInst* fast = builder.CreateIntrinsic(ret_ty, llvm::Intrinsic::sqrt, {base});
    annotate_generated_instruction(*fast, desc);
    builder.CreateBr(merge_block);

    builder.SetInsertPoint(slow_block);
    llvm::Value* slow = emit_slow();
    builder.CreateBr(merge_block);

    builder.SetInsertPoint(merge_block);
    _interp->_block->set_tail_llvm_block(merge_block);
    llvm::PHINode* result = builder.CreatePHI(ret_ty, 2, "pow_0dot5.result");
    result->addIncoming(fast, fast_block);
    result->addIncoming(slow, slow_block);
    _interp->_jvm->dpush(result);
    return true;
  }

  // General path.
  _interp->_jvm->dpush(emit_slow());
  return true;
}

bool JeandleIntrinsicLowering::lower_barrier_semantic(const JeandleIntrinsicDescriptor& desc) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::AtomicOrdering ordering;
  switch (desc.id) {
    case vmIntrinsics::_loadFence:  ordering = llvm::AtomicOrdering::Acquire;                break;
    case vmIntrinsics::_storeFence: ordering = llvm::AtomicOrdering::Release;                break;
    case vmIntrinsics::_fullFence:  ordering = llvm::AtomicOrdering::SequentiallyConsistent; break;
    default: return false;
  }
  _interp->_jvm->apop(); // Unsafe receiver (invokevirtual, no other args)
  llvm::FenceInst* fence = builder.CreateFence(ordering);
  annotate_generated_instruction(*fence, desc);
  return true;
}

// Preconditions.checkIndex(int|long index, int|long length, BiFunction exceptionFactory)
//   -> int|long
//
// Guard: length < 0 || (uint)index >= (uint)length.  See the original commentary;
// the two-level check distinguishes precondition failure (length < 0,
// Reason_intrinsic) from a true range failure (Reason_range_check).
bool JeandleIntrinsicLowering::lower_preconditions_check_index(const JeandleIntrinsicDescriptor& desc) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  int cur_bci = _interp->_bytecodes.cur_bci();
  bool is_long = desc.id == vmIntrinsics::_Preconditions_checkLongIndex;

  // Peek logical values so the operand stack stays intact for the deopt bundle
  // captured by uncommon_trap; the real pops are deferred to the pass path.
  llvm::Value* exception_factory = _interp->_jvm->peek_value(0).value();
  llvm::Value* length            = _interp->_jvm->peek_value(1).value();
  llvm::Value* index             = _interp->_jvm->peek_value(2).value();
  (void)exception_factory;

  llvm::Type* integer_ty = is_long ? llvm::Type::getInt64Ty(ctx)
                                   : llvm::Type::getInt32Ty(ctx);
  llvm::Value* zero = llvm::ConstantInt::get(integer_ty, 0);

  llvm::BasicBlock* pass = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_pass", _interp->_llvm_func);
  llvm::BasicBlock* mid  = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_mid", _interp->_llvm_func);
  llvm::BasicBlock* fail_pre = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_fail_pre", _interp->_llvm_func);
  llvm::BasicBlock* fail_range = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_fail_range", _interp->_llvm_func);

  llvm::Value* len_neg = builder.CreateICmp(llvm::CmpInst::ICMP_SLT, length, zero,
                                            "checkIndex.len_neg");
  llvm::BranchInst* br_len = builder.CreateCondBr(len_neg, fail_pre, mid);
  annotate_generated_instruction(*br_len, desc);

  builder.SetInsertPoint(mid);
  llvm::Value* idx_oob = builder.CreateICmp(llvm::CmpInst::ICMP_UGE, index, length,
                                            "checkIndex.idx_oob");
  llvm::BranchInst* br_idx = builder.CreateCondBr(idx_oob, fail_range, pass);
  annotate_generated_instruction(*br_idx, desc);

  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_maybe_recompile, fail_pre);
  _interp->uncommon_trap(Deoptimization::Reason_range_check,
                         Deoptimization::Action_maybe_recompile, fail_range);

  builder.SetInsertPoint(pass);
  _interp->_block->set_tail_llvm_block(pass);
  _interp->_jvm->apop(); // exception_factory
  if (is_long) {
    _interp->_jvm->lpop(); // length
    _interp->_jvm->lpop(); // index
  } else {
    _interp->_jvm->ipop(); // length
    _interp->_jvm->ipop(); // index
  }

  llvm::Value* len_nonneg = builder.CreateICmp(llvm::CmpInst::ICMP_SGE, length, zero,
                                               "checkIndex.len_nonneg");
  llvm::Value* non_neg    = builder.CreateICmp(llvm::CmpInst::ICMP_SGE, index, zero,
                                               "checkIndex.nonneg");
  llvm::Value* below_len  = builder.CreateICmp(llvm::CmpInst::ICMP_SLT, index, length,
                                               "checkIndex.below_len");
  builder.CreateIntrinsic(llvm::Intrinsic::assume, llvm::ArrayRef<llvm::Type*>{}, {len_nonneg});
  builder.CreateIntrinsic(llvm::Intrinsic::assume, llvm::ArrayRef<llvm::Type*>{}, {non_neg});
  builder.CreateIntrinsic(llvm::Intrinsic::assume, llvm::ArrayRef<llvm::Type*>{}, {below_len});

  if (is_long) {
    _interp->_jvm->lpush(index);
  } else {
    _interp->_jvm->ipush(index);
  }
  return true;
}

// StringCoding.countPositives(byte[] ba, int off, int len) → int
//
// Hybrid: precondition guards (deopt) + arrayBase/offset GEP, then a gc-leaf
// RuntimeCall to the SIMD adapter (or scalar fallback) resolved by the
// entrypoint layer.  Stack order (top first): len (int), off (int), ba (aref).
bool JeandleIntrinsicLowering::lower_count_positives(const JeandleIntrinsicDescriptor& desc) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;

  // Peek without popping so the deopt bundle captured by string_range_check sees
  // all three arguments on the stack.
  llvm::Value* len = _interp->_jvm->raw_peek(0).value();
  llvm::Value* off = _interp->_jvm->raw_peek(1).value();
  llvm::Value* ba  = _interp->_jvm->raw_peek(2).value();

  _interp->string_range_check(ba, off, len);

  _interp->_jvm->ipop(); // len
  _interp->_jvm->ipop(); // off
  _interp->_jvm->apop(); // ba

  // ba_start = ba + array_base_offset(T_BYTE) + off.
  llvm::Value* base_off   = builder.getInt32(arrayOopDesc::base_offset_in_bytes(T_BYTE));
  llvm::Value* array_base = builder.CreateInBoundsPtrAdd(ba, base_off, "ba_base");
  llvm::Value* ba_start   = builder.CreateInBoundsGEP(
      llvm::Type::getInt8Ty(ctx), array_base, off, "ba_start");

  JeandleIntrinsicEntrypoint entry;
  if (!JeandleIntrinsicEntrypoints::resolve_count_positives(_interp->_module, entry)) {
    return false;
  }
  _interp->_jvm->ipush(emit_runtime_call(desc, entry, {ba_start, len}));
  return true;
}

// _blackhole: consume all arguments via volatile inline asm to prevent DCE.
bool JeandleIntrinsicLowering::lower_blackhole(const JeandleIntrinsicDescriptor& desc) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;

  ciSignature* sig = _target->signature();

  for (int i = sig->count() - 1; i >= 0; i--) {
    BasicType bt = sig->type_at(i)->basic_type();
    llvm::Value* val;
    switch (bt) {
      case T_INT: case T_BOOLEAN: case T_BYTE: case T_CHAR: case T_SHORT:
        val = _interp->_jvm->ipop();
        break;
      case T_LONG:
        val = _interp->_jvm->lpop();
        break;
      case T_FLOAT:
        val = builder.CreateBitCast(_interp->_jvm->fpop(), builder.getInt32Ty());
        break;
      case T_DOUBLE:
        val = builder.CreateBitCast(_interp->_jvm->dpop(), builder.getInt64Ty());
        break;
      case T_OBJECT: case T_ARRAY:
        val = builder.CreatePtrToInt(_interp->_jvm->apop(), builder.getInt64Ty());
        break;
      default:
        return false;
    }
    auto* fn_ty = llvm::FunctionType::get(
        llvm::Type::getVoidTy(ctx), {val->getType()}, false);
    // No ~{memory} clobber: blackhole keeps SSA values live (prevent DCE) but is
    // not a memory barrier.
    auto* ia = llvm::InlineAsm::get(fn_ty, "", "r", /*hasSideEffects=*/true);
    llvm::CallInst* call = builder.CreateCall(ia, {val});
    annotate_generated_instruction(*call, desc);
  }

  if (!_target->is_static()) {
    _interp->_jvm->apop();
  }

  return true;
}
