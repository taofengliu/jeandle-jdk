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

#ifndef SHARE_JEANDLE_INTRINSIC_SUPPORT_HPP
#define SHARE_JEANDLE_INTRINSIC_SUPPORT_HPP

#include "jeandle/jeandleIntrinsicRegistry.hpp"

// Runtime-path availability for one intrinsic id, computed at decision time: is a
// HotSpot stub installed, and is a SharedRuntime fallback present.  CPU-feature
// support for an llvm builtin is a *separate* query (cpu_supports_llvm_builtin) so
// each caller asks only about the category it cares about — resolve_runtime_callee
// never sees a CPU bit it does not use.
//
// Part of the Jeandle analog of C2Compiler::is_intrinsic_supported: a pure
// availability query, separate from the policy that ranks those paths.
//
// The split is intentional and addresses three different kinds of facts:
//   descriptor            - what we declared statically about the intrinsic
//   runtime availability  - which runtime paths exist right now in this VM
//   decision (Policy)     - which path we actually picked, given priorities
struct JeandleRuntimeAvailability {
  // The per-intrinsic platform stub has been installed.
  bool has_hotspot_stub;
  // A SharedRuntime C-linkage fallback function exists.
  bool has_shared_runtime;
  bool any_runtime() const { return has_hotspot_stub || has_shared_runtime; }
};

class JeandleIntrinsicSupport : public AllStatic {
 public:
  // The runtime-stub availability for the given intrinsic id (stub installed /
  // SharedRuntime present); callers apply their own candidate policy.  Keyed
  // purely on the id (no descriptor / call_info), so a Hybrid body queries it the
  // same way a data-driven Call does.  This is a *generic* entry point: adding an
  // intrinsic adds a `case` to the internal probe, never a new query method.
  static JeandleRuntimeAvailability runtime_availability(vmIntrinsics::ID id);

  // Whether the target CPU can lower the given intrinsic's llvm.* builtin to a
  // native instruction (e.g. floor/ceil/rint need SSE4.1 ROUNDSD on x86).  The CPU
  // counterpart to runtime_availability — likewise generic and id-keyed (a new
  // intrinsic adds a `case`, not a function).  Returns true for intrinsics with no
  // CPU requirement, so PureLLVM builtin lowering can gate on it directly.
  static bool cpu_supports_llvm_builtin(vmIntrinsics::ID id);
};

#endif // SHARE_JEANDLE_INTRINSIC_SUPPORT_HPP
