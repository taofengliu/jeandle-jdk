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

#ifndef SHARE_JEANDLE_INTRINSIC_ENTRYPOINTS_HPP
#define SHARE_JEANDLE_INTRINSIC_ENTRYPOINTS_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/CallingConv.h"
#include "llvm/IR/Module.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "memory/allocation.hpp"

// Materialized runtime callee plus the IR-level facts a lowering needs to emit a
// call to it.  The libm math family is resolved directly from JeandleIntrinsicCallInfo's
// stub_callee_fn / shared_callee_fn function pointers (see
// JeandleIntrinsicLowering::resolve_runtime_callee), so it no longer needs a
// resolver here.  countPositives keeps a dedicated resolver because it chooses
// between a SIMD adapter stub and a scalar fallback that do not follow the
// id-based naming convention.
struct JeandleIntrinsicEntrypoint {
  llvm::FunctionCallee callee;
  llvm::CallingConv::ID calling_conv;
  bool is_gc_leaf;
};

class JeandleIntrinsicEntrypoints : public AllStatic {
 public:
  static bool resolve_count_positives(llvm::Module& module,
                                      JeandleIntrinsicEntrypoint& out);
};

#endif // SHARE_JEANDLE_INTRINSIC_ENTRYPOINTS_HPP
