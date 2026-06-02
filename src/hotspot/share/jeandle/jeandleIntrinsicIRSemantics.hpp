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

#ifndef SHARE_JEANDLE_INTRINSIC_IR_SEMANTICS_HPP
#define SHARE_JEANDLE_INTRINSIC_IR_SEMANTICS_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/InstrTypes.h"
#include "llvm/IR/Value.h"

#include "jeandle/jeandleIntrinsicCallInfo.hpp"

class JeandleAbstractInterpreter;
struct JeandleIntrinsicEntrypoint;

// Translates a lowered intrinsic into LLVM IR-level facts consumed by downstream
// passes: the deopt operand bundle and the gc-leaf-function attribute.
//
//   - build_operand_bundles attaches the "deopt" bundle carrying interpreter
//     state for a potential deopt at the call's safepoint; RewriteStatepointsForGC
//     threads it into gc.statepoint.
//   - annotate_instruction stamps the "gc-leaf-function" attribute, read by
//     RewriteStatepointsForGC to skip statepoint rewriting on leaf calls.
//
// annotate_instruction does NOT currently emit any jeandle.* metadata.  The
// descriptor carries barrier_kind as the GC barrier semantic that a future late
// GC-barrier LLVM pass will need (analogous to the array GC barrier late
// insertion), but how that semantic is threaded to LLVM is not decided yet, so
// nothing is stamped (see the TODO in the .cpp).  The observability-only keys
// (jeandle.intrinsic.id / jeandle.lowering.mode / jeandle.runtime.entry) were
// dropped in the call-shape refactor as they had no consumer.
class JeandleIntrinsicIRSemantics : public AllStatic {
 public:
  static llvm::SmallVector<llvm::OperandBundleDef, 1> build_operand_bundles(
      JeandleAbstractInterpreter* interp, bool attach_deopt_bundle);
  static void annotate_instruction(llvm::Instruction& inst,
                                   const JeandleIntrinsicDescriptor& desc,
                                   const JeandleIntrinsicEntrypoint* entry = nullptr);
  // Translate the call_info's memory flags into an LLVM `memory()` call-site
  // attribute.  The caller (emit_callsite) applies it only on the plain-call
  // path; the helper itself is also a no-op for any call that could safepoint
  // (gc-state / deopt / exception edge).
  static void apply_memory_attr(llvm::CallBase* call, const JeandleIntrinsicCallInfo& ci);
};

#endif // SHARE_JEANDLE_INTRINSIC_IR_SEMANTICS_HPP
