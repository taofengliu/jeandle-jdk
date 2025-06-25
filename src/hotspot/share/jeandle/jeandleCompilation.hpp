/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 *
 */

#ifndef SHARE_JEANDLE_COMPILATION_HPP
#define SHARE_JEANDLE_COMPILATION_HPP

#include <cassert>
#include "llvm/IR/Module.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/LLVMContext.h"

#include <memory>

#include "jeandle/jeandleCompiledCode.hpp"

#include "utilities/debug.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciMethod.hpp"
#include "memory/arena.hpp"

class JeandleCompiler;
class JeandleCompilation : public StackObj {
 public:
  JeandleCompilation(JeandleCompiler* compiler, ciEnv* env, ciMethod* method,
                     int entry_bci, bool install_code, llvm::MemoryBuffer* template_buffer);
  ~JeandleCompilation() = default;

  static JeandleCompilation* current() { return (JeandleCompilation*) ciEnv::current()->compiler_data(); }

  // Error related:
  void report_error(const char* msg) {
    if (msg != nullptr) {
      _error_msg = msg;
    }
  }
  bool error_occurred() const { return _error_msg != nullptr; }
  static void report_jeandle_error(const char* msg) { JeandleCompilation::current()->report_error(msg); }
  static bool jeandle_error_occurred() { return JeandleCompilation::current()->error_occurred(); }

  Arena* arena() { return _arena; }

 private:
  Arena* _arena; // Hold compilation life-time objects (JeandleCompilationResourceObj).
  JeandleCompiler* _compiler;
  ciEnv* _env;
  ciMethod* _method;
  int _entry_bci;
  llvm::LLVMContext _context;
  std::unique_ptr<llvm::Module> _llvm_module;
  llvm::Function* _llvm_func;
  std::string _comp_start_time;

  JeandleCompiledCode _code; // Compiled code.

  const char* _error_msg;

  void initialize(llvm::MemoryBuffer* template_buffer);
  void compile_java_method();
  void compile_module();
  void install_code();

  void dump_obj();
  void dump_ir(bool optimized);
};

class FuncSigAnalyze : public AllStatic {
 public:
  static llvm::Function* get(ciMethod* method, llvm::Module& module);
  static std::string method_name(ciMethod* method);
  static void setup_description(llvm::Function* func);
};

#endif // SHARE_JEANDLE_COMPILATION_HPP
