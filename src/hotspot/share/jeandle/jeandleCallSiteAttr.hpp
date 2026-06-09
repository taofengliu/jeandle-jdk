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

#ifndef SHARE_JEANDLE_CALL_SITE_ATTR_HPP
#define SHARE_JEANDLE_CALL_SITE_ATTR_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InlineAsm.h"
#include "llvm/IR/InstrTypes.h"
#include "llvm/IR/Value.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allocation.hpp"

class JeandleAbstractInterpreter;

// =============================================================================
// Control-flow facts for a lowered intrinsic call site. Combined into
// CallSiteAttributeMetadata::control_flags with bitwise OR.
// =============================================================================
enum JeandleControlFlag : uint8_t {
  CTRL_NONE                 = 0,
  // The lowering can transfer control to uncommon_trap / deopt.
  CTRL_MAY_DEOPT            = 1u << 0,
  // The call may throw a Java exception and needs invoke-style exception
  // continuation handling.
  CTRL_NEEDS_EXCEPTION_EDGE = 1u << 1,
};

// =============================================================================
// Memory-effect facts for a lowered intrinsic call site. Combined into
// CallSiteAttributeMetadata::memory_flags with bitwise OR and translated into
// LLVM call-site memory attributes where safe.
// =============================================================================
enum JeandleMemoryFlag : uint16_t {
  MEM_NONE              = 0,
  MEM_READ              = 1u << 0,
  MEM_WRITE             = 1u << 1,
  MEM_ORDERING_ONLY     = 1u << 2,
  MEM_NEEDS_GC_STATE    = 1u << 3,
};

// =============================================================================
// CallSiteAttributeMetadata — IR-level call-site facts. Narrowly focused on
// attributes that affect LLVM IR lowering (deopt bundles, exception edges,
// GC visibility, memory effects).
// =============================================================================
struct CallSiteAttributeMetadata {
  uint8_t  control_flags;   // bitmask of JeandleControlFlag
  uint16_t memory_flags;    // bitmask of JeandleMemoryFlag

  bool may_deopt()            const { return (control_flags & CTRL_MAY_DEOPT) != 0; }
  bool needs_exception_edge() const { return (control_flags & CTRL_NEEDS_EXCEPTION_EDGE) != 0; }
  bool reads_memory()         const { return (memory_flags  & MEM_READ) != 0; }
  bool writes_memory()        const { return (memory_flags  & MEM_WRITE) != 0; }
  bool only_orders_memory()   const { return (memory_flags  & MEM_ORDERING_ONLY) != 0; }
  bool needs_gc_state()       const { return (memory_flags  & MEM_NEEDS_GC_STATE) != 0; }
  bool attach_deopt_bundle()  const {
    return may_deopt() || needs_gc_state() || needs_exception_edge();
  }
  bool gc_leaf_by_flags()     const {
    return !needs_gc_state() && !may_deopt() && !needs_exception_edge();
  }
};

// =============================================================================
// JeandleRuntimeCalleeFn — function pointer type for runtime stub resolvers.
// Each resolver materializes the stub/SharedRuntime FunctionCallee in the
// given LLVM module.
// =============================================================================
using JeandleRuntimeCalleeFn = llvm::FunctionCallee (*)(llvm::Module&);

// =============================================================================
// JeandleIntrinsicEntrypoint — a resolved (materialized) runtime callee plus
// the IR-level facts needed to emit a call to it.
// =============================================================================
struct JeandleIntrinsicEntrypoint {
  llvm::FunctionCallee  callee;
  llvm::CallingConv::ID calling_conv;
  bool                  is_gc_leaf;
};

// =============================================================================
// Call-site IR annotation helpers.
// =============================================================================

// Build the "deopt" operand bundle carrying interpreter state for a potential
// deopt at the call's safepoint.
llvm::SmallVector<llvm::OperandBundleDef, 1> build_operand_bundles(
    JeandleAbstractInterpreter* interp, bool attach_deopt_bundle);

// Stamp the gc-leaf-function attribute on a call site when the call-site
// metadata or runtime entry indicates a leaf call.
void annotate_call(llvm::CallBase* call,
                   const CallSiteAttributeMetadata& attrs,
                   bool is_gc_leaf_entry = false);

// Translate memory flags into LLVM call-site memory attributes. Only applied
// when the call is safe from LLVM's reordering perspective (no GC-state, no
// deopt, no exception edge).
void apply_memory_attr(llvm::CallBase* call,
                       const CallSiteAttributeMetadata& attrs);

// Emit a side-effecting inline-asm call and mark it gc-leaf. Used by the
// blackhole sink.
llvm::CallInst* emit_gc_leaf_inline_asm(llvm::IRBuilder<>& builder,
                                        llvm::FunctionType* fn_ty,
                                        llvm::StringRef asm_string,
                                        llvm::StringRef constraints,
                                        llvm::ArrayRef<llvm::Value*> args);

#endif // SHARE_JEANDLE_CALL_SITE_ATTR_HPP
