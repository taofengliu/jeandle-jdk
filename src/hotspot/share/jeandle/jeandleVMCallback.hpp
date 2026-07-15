/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy of the LICENSE file included with
 * this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

#ifndef SHARE_JEANDLE_VM_CALLBACK_HPP
#define SHARE_JEANDLE_VM_CALLBACK_HPP

#include "memory/allocation.hpp"

// JeandleVMCallback collects the VM callbacks that the LLVM-side optimization
// pipeline queries during a Jeandle compilation. All members are static.
//
// The class is a friend of ciEnv so that the callbacks can query runtime information
// from ciEnv.
class JeandleVMCallback : public AllStatic {
 public:
  // Register all callbacks with the LLVM pipeline. Called once from
  // JeandleCompiler::initialize().
  static void register_callbacks();

  // Type hierarchy / declared-field queries.
  static bool      is_subtype(uintptr_t sub_klass, uintptr_t super_klass);
  static uintptr_t get_common_super_klass(uintptr_t k1, uintptr_t k2);
  static uintptr_t get_field_type(uintptr_t klass_ptr, int offset);
  static bool      is_interface(uintptr_t klass_ptr);
  static bool      is_object_klass(uintptr_t klass_ptr);
  static bool      is_unverified_interface(uintptr_t klass_ptr);
  static bool      is_effectively_final(uintptr_t klass_ptr);

  // Constant field folding.
  static int64_t   get_constant_field_value(int oop_id, int offset);
  static int       get_constant_field_info(int oop_id, int offset);

  // Oop handles.
  static const char* get_oop_handle_name(int oop_id);
  static uintptr_t   get_oop_klass(int oop_id);

  // Inlining.
  static bool      get_inline_callee_ir(uintptr_t callee_method);
  static int64_t   get_new_statepoint_id(int64_t old_statepoint_id);
  static bool      is_ok_to_inline(int scope_id, int bci, uintptr_t callee_method);
  static bool      record_inline_result(int scope_id, int bci, uintptr_t callee_method, int result);
  static bool      record_inlining_complete();
};

#endif // SHARE_JEANDLE_VM_CALLBACK_HPP
