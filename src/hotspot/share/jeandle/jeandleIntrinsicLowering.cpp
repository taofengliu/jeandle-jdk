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

#include <string.h>

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/Analysis/ConstantFolding.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/InlineAsm.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicCallInfo.hpp"
#include "jeandle/jeandleIntrinsicIRSemantics.hpp"
#include "jeandle/jeandleIntrinsicRegistry.hpp"
#include "jeandle/jeandleIntrinsicSupport.hpp"
#include "jeandle/jeandleIntrinsicTable.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "oops/arrayOop.hpp"
#include "runtime/stubRoutines.hpp"

// =============================================================================
// File-local helpers
// =============================================================================

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

enum JeandleIntrinsicCandidateSelection : uint8_t {
  JICS_Auto,
  JICS_LLVM,
  JICS_Hybrid,
  JICS_Call,
};

static JeandleIntrinsicCandidateSelection intrinsic_candidate_selection() {
  const char* value = JeandleIntrinsicCandidate;
  if (value == nullptr || strcmp(value, "auto") == 0) {
    return JICS_Auto;
  }
  if (strcmp(value, "llvm") == 0) {
    return JICS_LLVM;
  }
  if (strcmp(value, "hybrid") == 0) {
    return JICS_Hybrid;
  }
  if (strcmp(value, "call") == 0) {
    return JICS_Call;
  }
  fatal("Invalid JeandleIntrinsicCandidate='%s': expected auto, llvm, hybrid, or call", value);
  return JICS_Auto;
}

static bool candidate_selection_allows(JeandleLoweringKind kind,
                                       JeandleIntrinsicCandidateSelection selection) {
  switch (selection) {
    case JICS_Auto:   return true;
    case JICS_LLVM:   return kind == LK_LLVM;
    case JICS_Hybrid: return kind == LK_HYBRID;
    case JICS_Call:   return kind == LK_CALL;
  }
  ShouldNotReachHere();
  return false;
}

// =============================================================================
// Common call-site emission — the shared machinery used by the data-driven
// LK_CALL path and by Hybrid handlers.  emit_callsite consumes a
// JeandleCallSiteContract only (never desc.call_info), so those handlers can
// feed it contracts built on the fly.
// =============================================================================

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
                                                        const JeandleCallSiteContract& contract,
                                                        const JeandleIntrinsicEntrypoint* entry) {
  // emit_callsite consumes the call-site contract only — never desc.call_info —
  // so Hybrid handlers can build contracts on the fly.
  llvm::SmallVector<llvm::OperandBundleDef, 1> bundles =
    JeandleIntrinsicIRSemantics::build_operand_bundles(_interp, contract.attach_deopt_bundle());
  llvm::CallBase* site;
  if (contract.needs_exception_edge()) {
    site = _interp->create_call_ex(callee, args, calling_conv, bundles);
  } else {
    // Plain call: the intrinsic raises no Java exception.  Mark nounwind so LLVM
    // does not conservatively treat it as a potential unwind point.
    site = _interp->create_call(callee, args, calling_conv, bundles);
    site->setDoesNotThrow();
    JeandleIntrinsicIRSemantics::apply_memory_attr(site, contract);
  }
  JeandleIntrinsicIRSemantics::annotate_call(site, desc, contract, entry);
  attach_callee_return_klass_attr(site);
  return site;
}

llvm::CallBase* JeandleIntrinsicLowering::emit_java_op_call(const JeandleIntrinsicDescriptor& desc,
                                                            llvm::ArrayRef<llvm::Value*> args) {
  const JeandleIntrinsicCallInfo* ci = desc.call_info;
  assert(ci != nullptr && ci->java_op_name != nullptr, "JavaOp lowering requires a JavaOp symbol");
  llvm::Function* java_op = _interp->_module.getFunction(ci->java_op_name);
  assert(java_op != nullptr, "invalid JavaOp");
  // The JavaOp body is inlined later by jeandle-llvm's JavaOperationLower, which
  // matches on the callee's "lower-phase" attribute — the call site itself needs
  // no marker attribute.  A JavaOp is always a data-driven Call, so its contract
  // comes from the static call_info.
  return emit_callsite(desc, java_op, llvm::CallingConv::Hotspot_JIT, args, ci->contract);
}

// Resolve the HotSpot stub / SharedRuntime routine from the given resolver function
// pointers, filling `entry`.  Returns false without touching the operand stack if no
// runtime routine exists, so the caller can decline the selected candidate or use
// its own fallback.  Takes the callee identity explicitly, so a Hybrid handler
// can resolve without a call_info.
bool JeandleIntrinsicLowering::resolve_runtime_callee(vmIntrinsics::ID id,
                                                      JeandleRuntimeCalleeFn stub_fn,
                                                      JeandleRuntimeCalleeFn shared_fn,
                                                      JeandleIntrinsicEntrypoint& entry) {
  const JeandleRuntimeAvailability avail = JeandleIntrinsicSupport::runtime_availability(id);
  JeandleRuntimeCalleeFn fn = nullptr;
  if (avail.has_hotspot_stub && stub_fn != nullptr) {
    fn = stub_fn;
  } else if (avail.has_shared_runtime && shared_fn != nullptr) {
    fn = shared_fn;
  }
  if (fn != nullptr) {
    entry.callee = fn(_interp->_module);
    entry.calling_conv = llvm::CallingConv::C;
    entry.is_gc_leaf = true;
    return true;
  }
  return false;
}

// =============================================================================
// lower() — entry point.  Fixed-priority traversal over the declared candidate
// kinds: LK_LLVM > LK_HYBRID > LK_CALL.  Try each declared candidate in order; the
// first that lowers wins.  A multi-candidate intrinsic (e.g. dsin = LK_LLVM |
// LK_CALL) falls through to the next candidate when the higher-priority one declines.
// JeandleIntrinsicCandidate is a diagnostic override for path testing: auto keeps
// the traversal above, while llvm / hybrid / call masks the other candidates.
// =============================================================================

bool JeandleIntrinsicLowering::lower(const JeandleIntrinsicDescriptor& desc,
                                     const ciMethod* target) {
  _target = target;
  const JeandleIntrinsicCandidateSelection selection = intrinsic_candidate_selection();

  if (candidate_selection_allows(LK_LLVM, selection) &&
      (desc.lowering_kinds & LK_LLVM) && lower_llvm(desc)) {
    return true;
  }

  if (candidate_selection_allows(LK_HYBRID, selection) &&
      (desc.lowering_kinds & LK_HYBRID)) {
    switch (desc.id) {
      // Dispatch generated from the same shared table as the descriptor row.
      // handler_suffix is token-pasted to lower_<handler_suffix>(desc).
#define JEANDLE_HYBRID_DISPATCH(VM_NAME, HANDLER_SUFFIX) \
      case vmIntrinsics::_##VM_NAME: return lower_##HANDLER_SUFFIX(desc);
      JEANDLE_HYBRID_HANDLER_TABLE(JEANDLE_HYBRID_DISPATCH)
#undef JEANDLE_HYBRID_DISPATCH
      default: ShouldNotReachHere(); break;  // unreachable: every LK_HYBRID row dispatches here
    }
  }

  if (candidate_selection_allows(LK_CALL, selection) &&
      (desc.lowering_kinds & LK_CALL)) {
    // LK_CALL is fully data-driven.
    return emit_simple_call_intrinsic(desc);
  }

  return false;
}

// =============================================================================
// LK_CALL — data-driven opaque call (runtime stub / SharedRuntime / JavaOp).  Pops
// the args (shape derived from the target method signature), emits one call, pushes
// the result.  No per-intrinsic code; the callee + contract are read off call_info.
// =============================================================================

bool JeandleIntrinsicLowering::emit_simple_call_intrinsic(const JeandleIntrinsicDescriptor& desc) {
  const JeandleIntrinsicCallInfo* ci = desc.call_info;
  assert(ci != nullptr, "Call lowering requires call_info");

  // Resolve the callee before popping args, so a miss can decline cleanly without
  // disturbing the operand stack.
  bool is_java_op = false;
  JeandleIntrinsicEntrypoint entry;

  switch (ci->callee_kind) {
    case JeandleIntrinsicCalleeKind::JavaOp:
      is_java_op = true;
      break;
    case JeandleIntrinsicCalleeKind::RuntimeStub:
      // The Call candidate is the runtime stub / SharedRuntime only (the llvm
      // builtin, when one exists, is a separate LK_LLVM candidate).  This branch
      // runs only when the runtime Call candidate is selected: resolve the
      // installed stub first, then SharedRuntime, or decline this candidate.
      if (!resolve_runtime_callee(desc.id, ci->stub_callee_fn, ci->shared_callee_fn, entry)) {
        return false;
      }
      break;
    case JeandleIntrinsicCalleeKind::None:
      return false;  // a Call descriptor must name a generic callee
  }

  // The operand-stack shape is fully determined by the intercepted method's
  // signature: one slot per signature parameter, plus a leading receiver slot for
  // instance methods.  Pop in computational types (sub-word -> int, array -> object),
  // matching how the JVM operand stack stores them.
  ciSignature* sig = _target->signature();
  const bool has_receiver = !_target->is_static();
  const int sig_count = sig->count();
  const int arg_count = sig_count + (has_receiver ? 1 : 0);

  llvm::SmallVector<llvm::Value*, 4> args;
  llvm::SmallVector<BasicType, 4> arg_types;
  args.resize(arg_count);
  arg_types.resize(arg_count);
  for (int i = 0; i < arg_count; ++i) {
    arg_types[i] = (has_receiver && i == 0)
        ? T_OBJECT  // the receiver ('this')
        : JeandleType::actual2computational(sig->type_at(i - (has_receiver ? 1 : 0))->basic_type());
  }
  // Pop in reverse: the last argument is on top of the operand stack.
  for (int i = arg_count - 1; i >= 0; --i) {
    args[i] = _interp->_jvm->pop(arg_types[i]);
  }

  llvm::Value* result = is_java_op
      ? emit_java_op_call(desc, args)
      : emit_callsite(desc, entry.callee, entry.calling_conv, args, ci->contract, &entry);

  const BasicType result_type =
      JeandleType::actual2computational(sig->return_type()->basic_type());
  if (result_type != T_VOID) {
    _interp->_jvm->push(result_type, result);
  }
  return true;
}

// =============================================================================
// LK_LLVM — no-call IR, dispatched on an *op*: the LLVM IR operation that
// produces the intrinsic's result.  The inline op set is closed (a handful of IR
// builder calls), so it does NOT grow as intrinsics are added: a new LK_LLVM
// intrinsic is one shared table row, no new code unless it needs a genuinely new
// IR operation or a custom handler.  Operand/result types come from the target
// method signature, so there are no per-row type columns.
//
//   LO_BUILTIN — CreateIntrinsic(llvm_id): a single-argument llvm.* builtin.
//                CPU support is checked by id (floor/ceil/rint need SSE4.1 on x86).
//                A few llvm intrinsics (abs/ctlz/cttz) take a trailing i1
//                edge-case flag; that is derived from llvm_id and always passed
//                false (Java wants the poison-free result, e.g. abs(MIN_VALUE)).
//   LO_BITCAST — CreateBitCast between the (single) argument and the result type.
//   LO_FENCE   — CreateFence with an ordering derived from the id.
//   LO_SINK    — volatile inline-asm sink consuming every argument (blackhole).
//   LO_CUSTOM_HANDLER — custom no-call lowering (platform asm / guard+trap) that
//                does not fit an existing LO_* skeleton; the shared table carries
//                handler_suffix for lower_<handler_suffix>(desc).
//
// LK_LLVM rows carry no static CallInfo and no semantic call-site contract.  Inline
// ops emit bare IR or llvm.* and need no deopt bundle / gc-leaf annotation because
// RS4GC never rewrites them to a statepoint.  Custom handlers may emit guards or
// traps; trap deopt bundles are owned by the uncommon_trap helper.  The lone inline
// op exception is LO_SINK inline asm, which RS4GC *would* try to statepoint, so it
// is stamped gc-leaf via IRSemantics::emit_gc_leaf_inline_asm.  This is exactly why
// these belong here and not on the opaque-call path.
// =============================================================================
enum LlvmOp : uint8_t { LO_BUILTIN, LO_BITCAST, LO_FENCE, LO_SINK, LO_CUSTOM_HANDLER };

struct LlvmOpSpec {
  vmIntrinsics::ID    vm_id;
  LlvmOp              op;
  llvm::Intrinsic::ID llvm_id;         // LO_BUILTIN only (else not_intrinsic)
};

static constexpr LlvmOpSpec kLlvmOpTable[] = {
  // Data-driven LLVM ops (operand/result types derived from the signature).
#define JEANDLE_LLVM_OP_ROW(VM_NAME, OP, LLVM_NAME) \
  { vmIntrinsics::_##VM_NAME, OP, llvm::Intrinsic::LLVM_NAME },
  JEANDLE_LLVM_INLINE_OP_TABLE(JEANDLE_LLVM_OP_ROW)
#undef JEANDLE_LLVM_OP_ROW

  // Custom no-call handlers share the LK_LLVM descriptor table, but dispatch
  // through lower_<handler_suffix> below.
#define JEANDLE_LLVM_CUSTOM_HANDLER_OP_ROW(VM_NAME, HANDLER_SUFFIX) \
  { vmIntrinsics::_##VM_NAME, LO_CUSTOM_HANDLER, llvm::Intrinsic::not_intrinsic },
  JEANDLE_LLVM_CUSTOM_HANDLER_TABLE(JEANDLE_LLVM_CUSTOM_HANDLER_OP_ROW)
#undef JEANDLE_LLVM_CUSTOM_HANDLER_OP_ROW
};

static const LlvmOpSpec* find_llvm_op(vmIntrinsics::ID id) {
  for (const LlvmOpSpec& spec : kLlvmOpTable) {
    if (spec.vm_id == id) return &spec;
  }
  return nullptr;
}

bool JeandleIntrinsicLowering::lower_llvm(const JeandleIntrinsicDescriptor& desc) {
  const LlvmOpSpec* spec = find_llvm_op(desc.id);
  if (spec == nullptr) {
    // lowering_kinds advertised LK_LLVM for this id, but kLlvmOpTable has no row.
    // With the shared row list this should only happen if a new LLVM mechanism
    // tag is added without expanding it here.
    ShouldNotReachHere();
    return false;
  }
  switch (spec->op) {
    case LO_BUILTIN:
      // CPU-feature gate (floor/ceil/rint need SSE4.1 on x86): decline before
      // touching the operand stack so the traversal falls through to the next
      // candidate (or NormalInvoke).  The query is id-keyed and returns true for
      // intrinsics with no CPU requirement.
      if (!JeandleIntrinsicSupport::cpu_supports_llvm_builtin(desc.id)) {
        return false;
      }
      return emit_llvm_builtin(desc, spec->llvm_id);
    case LO_BITCAST:
      return emit_llvm_bitcast(desc);
    case LO_FENCE:
      return emit_llvm_fence(desc);
    case LO_SINK:
      return emit_llvm_sink(desc);
    case LO_CUSTOM_HANDLER:
      switch (desc.id) {
        // Dispatch generated from the same shared table as the descriptor row.
        // handler_suffix is token-pasted to lower_<handler_suffix>(desc).
#define JEANDLE_LLVM_CUSTOM_HANDLER_DISPATCH(VM_NAME, HANDLER_SUFFIX) \
        case vmIntrinsics::_##VM_NAME: return lower_##HANDLER_SUFFIX(desc);
        JEANDLE_LLVM_CUSTOM_HANDLER_TABLE(JEANDLE_LLVM_CUSTOM_HANDLER_DISPATCH)
#undef JEANDLE_LLVM_CUSTOM_HANDLER_DISPATCH
        default: ShouldNotReachHere(); return false;  // unreachable: every LO_CUSTOM_HANDLER row dispatches here
      }
  }
  return false;  // unreachable: the switch is exhaustive over LlvmOp (-Wswitch enforces it)
}

// llvm.abs / ctlz / cttz take a trailing i1 edge-case flag (is_int_min_poison /
// is_zero_poison).  Java semantics always want the poison-free form — abs(MIN_VALUE)
// == MIN_VALUE, numberOfLeadingZeros(0) == 32 — so the flag is always emitted false.
// It is a property of the llvm intrinsic, not the Java intrinsic, so it is derived
// from llvm_id rather than stored per row.
static bool llvm_intrinsic_takes_trailing_i1(llvm::Intrinsic::ID id) {
  return id == llvm::Intrinsic::abs ||
         id == llvm::Intrinsic::ctlz ||
         id == llvm::Intrinsic::cttz;
}

// LO_BUILTIN: pop the single argument, CreateIntrinsic(llvm_id) overloaded on the
// argument type, push the result — truncated when the result type differs (e.g.
// Long.bitCount's i64 ctpop -> i32).  Types come from the target method signature.
bool JeandleIntrinsicLowering::emit_llvm_builtin(const JeandleIntrinsicDescriptor& desc,
                                                 llvm::Intrinsic::ID llvm_id) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  BasicType operand_type = sig->type_at(0)->basic_type();
  BasicType result_type  = sig->return_type()->basic_type();

  llvm::SmallVector<llvm::Value*, 2> args;
  args.push_back(_interp->_jvm->pop(operand_type));
  if (llvm_intrinsic_takes_trailing_i1(llvm_id)) {
    args.push_back(builder.getInt1(false));
  }

  llvm::CallInst* call = builder.CreateIntrinsic(
      JeandleType::java2llvm(operand_type, ctx), llvm_id, args);

  llvm::Value* result = call;
  if (result_type != operand_type) {
    result = builder.CreateTrunc(call, JeandleType::java2llvm(result_type, ctx));
  }
  _interp->_jvm->push(result_type, result);
  return true;
}

// LO_BITCAST: the Float/Int and Double/Long Raw-bits intrinsics are a single bitcast
// between the (single) argument type and the result type, both from the signature.
bool JeandleIntrinsicLowering::emit_llvm_bitcast(const JeandleIntrinsicDescriptor& desc) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  BasicType src_type = sig->type_at(0)->basic_type();
  BasicType dst_type = sig->return_type()->basic_type();

  llvm::Value* src = _interp->_jvm->pop(src_type);
  llvm::Value* cast = builder.CreateBitCast(src, JeandleType::java2llvm(dst_type, ctx));
  _interp->_jvm->push(dst_type, cast);
  return true;
}

// LO_FENCE: Unsafe.{load,store,full}Fence — pop the receiver, emit a CreateFence
// whose ordering is fixed by the id.  No result.
bool JeandleIntrinsicLowering::emit_llvm_fence(const JeandleIntrinsicDescriptor& desc) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::AtomicOrdering ordering;
  switch (desc.id) {
    case vmIntrinsics::_loadFence:  ordering = llvm::AtomicOrdering::Acquire;                break;
    case vmIntrinsics::_storeFence: ordering = llvm::AtomicOrdering::Release;                break;
    case vmIntrinsics::_fullFence:  ordering = llvm::AtomicOrdering::SequentiallyConsistent; break;
    default:
      // table marked this id LO_FENCE but no ordering is wired here.
      ShouldNotReachHere();
      return false;
  }
  _interp->_jvm->apop(); // Unsafe receiver (invokevirtual, no other args)
  builder.CreateFence(ordering);
  return true;
}

// LO_SINK (_blackhole): consume all arguments via volatile inline asm to prevent DCE.
bool JeandleIntrinsicLowering::emit_llvm_sink(const JeandleIntrinsicDescriptor& desc) {
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
        // blackhole argument of an unexpected basic type.
        ShouldNotReachHere();
        return false;
    }
    auto* fn_ty = llvm::FunctionType::get(
        llvm::Type::getVoidTy(ctx), {val->getType()}, false);
    // No ~{memory} clobber: blackhole keeps SSA values live (prevent DCE) but is
    // not a memory barrier.  The call is marked gc-leaf so RS4GC does not try to
    // statepoint the side-effecting inline asm.
    JeandleIntrinsicIRSemantics::emit_gc_leaf_inline_asm(builder, fn_ty, "", "r", {val});
  }

  if (!_target->is_static()) {
    _interp->_jvm->apop();
  }

  return true;
}

// Custom LK_LLVM handler.  Preconditions.checkIndex(int|long index, int|long length,
// BiFunction exceptionFactory) -> int|long.
//
// Guard: length < 0 || (uint)index >= (uint)length.  The two-level check
// distinguishes precondition failure (length < 0, Reason_intrinsic) from a true
// range failure (Reason_range_check).  (onSpinWait's LK_LLVM custom handler is
// platform-specific, in cpu/<arch>/jeandleIntrinsicLowering_<arch>.cpp.)
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
  builder.CreateCondBr(len_neg, fail_pre, mid);

  builder.SetInsertPoint(mid);
  llvm::Value* idx_oob = builder.CreateICmp(llvm::CmpInst::ICMP_UGE, index, length,
                                            "checkIndex.idx_oob");
  builder.CreateCondBr(idx_oob, fail_range, pass);

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

// =============================================================================
// LK_HYBRID — custom handlers that wrap call sites in guards / fast paths and
// build their own JeandleCallSiteContract (no static call_info).  The shared row
// generates both the descriptor and this dispatch.
// =============================================================================

// Math.pow(base, exp): IR fast paths for common constant exponents, otherwise the
// HotSpot pow routine — the generated platform stub when one is installed, else the
// SharedRuntime dpow (always present).  Math.pow must NOT fall back to llvm.pow:
// llvm.pow follows C/IEEE semantics (e.g. pow(1.0, NaN) == 1.0) which differ from
// Java Math.pow (pow(1.0, NaN) == NaN), so only the HotSpot/Java pow is spec-correct.
bool JeandleIntrinsicLowering::lower_pow_hybrid(const JeandleIntrinsicDescriptor& desc) {
  // pow is a pure leaf call: no deopt, no GC state, no exception edge.
  const JeandleCallSiteContract pow_contract = { CTRL_NONE, MEM_NONE };
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Type* ret_ty = JeandleType::java2llvm(T_DOUBLE, ctx);

  llvm::Value* exp = _interp->_jvm->dpop();
  llvm::Value* base = _interp->_jvm->dpop();

  // Constant fast path: pow(x, 2.0) => x * x.
  if (is_double_constant(exp, 2.0, _interp->_module.getDataLayout())) {
    llvm::Value* fast = builder.CreateFMul(base, base);
    _interp->_jvm->dpush(fast);
    return true;
  }

  // Resolve the Java-semantics pow routine once: the generated platform stub when
  // installed, otherwise the SharedRuntime dpow.  Passing both resolvers lets
  // resolve_runtime_callee pick stub-then-SharedRuntime; SharedRuntime::dpow always
  // exists, so resolution cannot fail.
  JeandleIntrinsicEntrypoint entry;
  const bool resolved = resolve_runtime_callee(desc.id,
                              &JeandleRuntimeRoutine::StubRoutines_dpow_callee,
                              &JeandleRuntimeRoutine::SharedRuntime_dpow_callee, entry);
  guarantee(resolved, "Math.pow needs a HotSpot dpow stub or SharedRuntime routine");

  // Emit a full pow(base, exp) call to the resolved HotSpot pow routine.
  auto emit_slow = [&]() -> llvm::Value* {
    return emit_callsite(desc, entry.callee, entry.calling_conv,
                         {base, exp}, pow_contract, &entry);
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

// StringCoding.countPositives(byte[] ba, int off, int len) -> int.
//
// Precondition guards (deopt) + arrayBase/offset GEP, then a gc-leaf RuntimeCall to
// the SIMD adapter (or scalar fallback) resolved by the entrypoint layer.  Stack
// order (top first): len (int), off (int), ba (aref).
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

  // Resolve the callee inline: prefer the platform SIMD adapter when its stub has
  // been generated, else the scalar C++ fallback.  These share no id-based naming
  // convention with the runtime, so the generic resolve_runtime_callee path (used by
  // the libm family) does not apply.
  JeandleIntrinsicEntrypoint entry;
  entry.calling_conv = llvm::CallingConv::C;
  entry.is_gc_leaf   = true;
  entry.callee = JeandleRuntimeRoutine::count_positives_stub_adapter() != nullptr
      ? JeandleRuntimeRoutine::JeandleRuntime_count_positives_adapter_callee(_interp->_module)
      : JeandleRuntimeRoutine::JeandleRuntime_count_positives_callee(_interp->_module);

  // The guards above may deopt; the scan call itself only reads the byte[].
  // Built here — countPositives carries no static call_info.
  const JeandleCallSiteContract scan_contract = { CTRL_NONE, MEM_READ };
  _interp->_jvm->ipush(emit_callsite(desc, entry.callee, entry.calling_conv, {ba_start, len}, scan_contract, &entry));
  return true;
}
