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

#include "jeandle/jeandleIntrinsicEntrypoints.hpp"

#include "jeandle/jeandleRuntimeRoutine.hpp"

static llvm::CallingConv::ID runtime_cc() {
  return llvm::CallingConv::C;
}

// The libm math routines (dsin/dcos/.../dpow) are resolved property-driven from
// JeandleCallInfo's stub_callee_fn / shared_callee_fn function pointers in
// JeandleIntrinsicLowering::resolve_runtime_callee — there is no id-switch here.
// Only countPositives keeps a dedicated resolver because it picks between a SIMD
// adapter stub and a scalar fallback that share no naming convention with the id.
bool JeandleIntrinsicEntrypoints::resolve_count_positives(llvm::Module& module,
                                                          JeandleIntrinsicEntrypoint& out) {
  out.calling_conv = runtime_cc();
  out.is_gc_leaf   = true;
  // Prefer the platform SIMD adapter when available; fall back to the scalar C++ wrapper.
  if (JeandleRuntimeRoutine::count_positives_stub_adapter() != nullptr) {
    out.callee = JeandleRuntimeRoutine::JeandleRuntime_count_positives_adapter_callee(module);
  } else {
    out.callee = JeandleRuntimeRoutine::JeandleRuntime_count_positives_callee(module);
  }
  return true;
}
