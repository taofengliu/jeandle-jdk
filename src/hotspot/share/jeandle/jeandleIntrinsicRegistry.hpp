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
 */

#ifndef SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP
#define SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "memory/allocation.hpp"
#include "runtime/deoptimization.hpp"

class ciMethod;

// Control-flow facts about an intrinsic.  Combined into descriptor.control_flags
// with bitwise OR.  Intentionally an unscoped enum so descriptors can write
// `CTRL_MAY_DEOPT | CTRL_NEEDS_EXCEPTION_EDGE` without operator overloads, and
// so unset entries can simply write CTRL_NONE.
enum JeandleControlFlag : uint8_t {
  CTRL_NONE                 = 0,
  // The intrinsic lowering can transfer control to uncommon_trap/deopt.
  CTRL_MAY_DEOPT            = 1u << 0,
  // The intrinsic may throw a Java exception from the lowered path and needs
  // invoke-style exception continuation handling, not just deopt replay.
  CTRL_NEEDS_EXCEPTION_EDGE = 1u << 1,
};

// Memory-effect facts about an intrinsic.  Combined into descriptor.memory_flags
// with bitwise OR and translated into LLVM call-site memory attributes where safe.
enum JeandleMemoryFlag : uint16_t {
  MEM_NONE              = 0,
  // The call reads LLVM-visible memory.  Combined with MEM_WRITE for RMW.
  MEM_READ              = 1u << 0,
  // The call writes LLVM-visible memory.
  MEM_WRITE             = 1u << 1,
  // The call only constrains memory ordering (fence-like).  Mutually exclusive
  // with MEM_READ / MEM_WRITE.
  MEM_ORDERING_ONLY     = 1u << 2,
  // The lowered IR/call must remain visible to GC-aware statepoint code.
  MEM_NEEDS_GC_STATE    = 1u << 3,
  MEM_BARRIER_WEAK_REFERENT_LOAD = 1u << 4, // Weak referent load with GC barrier.
  MEM_BARRIER_RAW_REFERENT_READ  = 1u << 5, // Raw referent read without GC barrier.
  MEM_BARRIER_CARD_MARK_POST     = 1u << 6, // Post-write card table mark.
  MEM_BARRIER_VOLATILE_LOAD      = 1u << 7, // Volatile load acquire semantics.
  MEM_BARRIER_VOLATILE_STORE     = 1u << 8, // Volatile store release semantics.
  MEM_BARRIER_MASK = MEM_BARRIER_WEAK_REFERENT_LOAD |
                     MEM_BARRIER_RAW_REFERENT_READ  |
                     MEM_BARRIER_CARD_MARK_POST     |
                     MEM_BARRIER_VOLATILE_LOAD      |
                     MEM_BARRIER_VOLATILE_STORE,
};

// What lowering paths a descriptor *declares* it can take.  Combined into
// descriptor.support_flags with bitwise OR.  Per-VM availability of those paths
// (stub installed, CPU feature present) is a runtime check in JeandleIntrinsicSupport.
enum JeandleSupportFlag : uint8_t {
  SUPPORT_NONE          = 0,
  // A HotSpot-generated stub or SharedRuntime fallback is an available impl.
  SUPPORT_HOTSPOT_STUB  = 1u << 0,
  // LLVM has a builtin or direct IR representation for this intrinsic.
  SUPPORT_LLVM_INTRIN   = 1u << 1,
};

enum class JeandleLoweringKind {
  PureIRInstruction, // lower to a bare LLVM IR instruction (bitcast, fence)
  PureLLVMBuiltin,   // lower to a named llvm.* builtin or LLVM target intrinsic
  RuntimeCall,       // emit a runtime/stub call selected by policy/support checks
  GuardedHybrid,     // policy-identical to RuntimeCall; the lowering function
                     // body additionally emits a fast/slow guard (e.g. pow(x,2))
  JavaOperation      // delegate complex semantics to a JavaOp runtime glue method
};

using JeandleTrapReasonMask = uint32_t;
static_assert(Deoptimization::Reason_LIMIT <= 32,
              "JeandleTrapReasonMask must be widened");

struct JeandleIntrinsicDescriptor {
  // VM intrinsic ID being described.  This is also the O(1) lookup-table key.
  vmIntrinsics::ID id;
  // Bitmask of JeandleControlFlag.
  uint8_t control_flags;
  // Bitmask of JeandleMemoryFlag.
  uint16_t memory_flags;
  // Coarse lowering family selected before capability/fallback refinement.
  JeandleLoweringKind lowering_kind;
  // Bitmask of JeandleSupportFlag declaring which lowering paths exist.
  uint8_t support_flags;
  // JavaOp symbol used only by JavaOperation descriptors; nullptr otherwise.
  const char* java_op_name;
  // Deoptimization reasons that throttle admission when too many traps occurred
  // at the invoke site.  Zero means no trap-based throttling.
  JeandleTrapReasonMask trap_throttle_mask;

  // Inline accessors so consumers can read named flags without bit-twiddling.
  bool may_deopt()             const { return (control_flags & CTRL_MAY_DEOPT) != 0; }
  bool needs_exception_edge()  const { return (control_flags & CTRL_NEEDS_EXCEPTION_EDGE) != 0; }
  bool reads_memory()          const { return (memory_flags  & MEM_READ) != 0; }
  bool writes_memory()         const { return (memory_flags  & MEM_WRITE) != 0; }
  bool only_orders_memory()    const { return (memory_flags  & MEM_ORDERING_ONLY) != 0; }
  bool needs_gc_state()        const { return (memory_flags  & MEM_NEEDS_GC_STATE) != 0; }
  uint16_t barrier_semantics() const { return memory_flags & MEM_BARRIER_MASK; }
  bool has_barrier_semantics() const { return barrier_semantics() != 0; }
  bool weak_referent_load_barrier() const { return (memory_flags & MEM_BARRIER_WEAK_REFERENT_LOAD) != 0; }
  bool raw_referent_read_barrier()  const { return (memory_flags & MEM_BARRIER_RAW_REFERENT_READ) != 0; }
  bool card_mark_post_barrier()     const { return (memory_flags & MEM_BARRIER_CARD_MARK_POST) != 0; }
  bool volatile_load_barrier()      const { return (memory_flags & MEM_BARRIER_VOLATILE_LOAD) != 0; }
  bool volatile_store_barrier()     const { return (memory_flags & MEM_BARRIER_VOLATILE_STORE) != 0; }
  bool supports_hotspot_stub() const { return (support_flags & SUPPORT_HOTSPOT_STUB) != 0; }
  bool supports_llvm_intrin()  const { return (support_flags & SUPPORT_LLVM_INTRIN) != 0; }
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
};

#endif // SHARE_JEANDLE_INTRINSIC_REGISTRY_HPP
