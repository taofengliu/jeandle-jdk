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

#include <cassert>
#include "llvm/ADT/SmallVector.h"
#include "llvm/Bitcode/BitcodeReader.h"
#include "llvm/Jeandle/Jeandle.h"
#include "llvm/IR/CallingConv.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/LegacyPassManager.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/Verifier.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Support/FileSystem.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Support/TargetSelect.h"
#include "llvm/Support/SmallVectorMemoryBuffer.h"
#include "llvm/Transforms/Utils.h"

#include <algorithm>
#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiler.hpp"
#include "jeandle/jeandleType.hpp"

#include "utilities/debug.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "logging/log.hpp"
#include "runtime/sharedRuntime.hpp"

JeandleCompilation::JeandleCompilation(JeandleCompiler* compiler,
                                       ciEnv* env,
                                       ciMethod* method,
                                       int entry_bci,
                                       bool should_install,
                                       llvm::MemoryBuffer* template_buffer) :
                                       _compiler(compiler),
                                       _env(env),
                                       _method(method),
                                       _entry_bci(entry_bci),
                                       _context(),
                                       _code(env, method),
                                       _error_msg(nullptr) {
  // Setup compilation.
  initialize(template_buffer);

  // Get the the LLVM function definition.
  _llvm_func = FuncSigAnalyze::get(method, *_llvm_module);

  // Let's compile.
  compile_java_method();

  if (error_occurred()) {
#ifdef ASSERT
    if (JeandleCrashOnError)
      report_vm_error(__FILE__, __LINE__, _error_msg);
#endif
    _env->record_method_not_compilable(_error_msg);
    return;
  }

  if (JeandleDumpObjects) {
    dump_obj();
  }

  // Install code.
  if (should_install) {
    install_code();
  }

}

void JeandleCompilation::install_code() {
  _env->register_method(_method,
                        _entry_bci,
                        _code.offsets(),
                        0, // temporary value
                        _code.code_buffer(),
                        _code.frame_size(),
                        _env->debug_info()->_oopmaps,
                        _code.exception_handler_table(),
                        _code.implicit_exception_table(),
                        _compiler,
                        false, // temporary value
                        false, // temporary value
                        false, // temporary value
                        0); // temporary value
}

void JeandleCompilation::initialize(llvm::MemoryBuffer* template_buffer) {
  _arena = Thread::current()->resource_area();
  _env->set_compiler_data(this);

  // Use an oop recorder bound to the CI environment.
  // (The default oop recorder is ignorant of the CI.)
  OopRecorder* ooprec = new OopRecorder(_env->arena());
  _env->set_oop_recorder(ooprec);
  _env->set_debug_info(new DebugInformationRecorder(ooprec));
  _env->debug_info()->set_oopmaps(new OopMapSet());
  _env->set_dependencies(new Dependencies(_env));

  // Get timestamp to mark dump file.
  auto now = std::chrono::system_clock::now();
  auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch());
  _comp_start_time = std::to_string(duration.count());

  // Get template module from global memory buffer.
  llvm::Expected<std::unique_ptr<llvm::Module>> module_or_error =
      parseBitcodeFile(template_buffer->getMemBufferRef(), _context);
  if (!module_or_error) {
    report_jeandle_error("Failed to parse template bitcode");
    return;
  }
  _llvm_module = std::move(module_or_error.get());
  assert(_llvm_module != nullptr, "invalid llvm module");

  _llvm_module->setModuleIdentifier(FuncSigAnalyze::method_name(_method));
  _llvm_module->setDataLayout(*(_compiler->data_layout()));
}

void JeandleCompilation::compile_java_method() {
  // Build basic blocks. Then fill basic blocks with LLVM IR.
  {
    JeandleAbstractInterpreter interpret(_method, _llvm_func, _entry_bci, *_llvm_module, _code);
  }

  if (error_occurred()) {
    return;
  }

#ifdef ASSERT
  // Verify.
  if (llvm::verifyFunction(*_llvm_func, &llvm::errs())) {
    report_error("verify failed");
    return;
  }
#endif

  if (JeandleDumpIR) {
    dump_ir(false);
  }

  // Optimize.
  llvm::jeandle::optimize(_llvm_module.get(), llvm::OptimizationLevel::O3);

  if (JeandleDumpIR) {
    dump_ir(true);
  }

  // Compile the module to an object file.
  compile_module();

  if (error_occurred()) {
    return;
  }

  // Unpack LLVM code information. Generate relocations, stubs and debug information.
  _code.finalize();
}

void JeandleCompilation::compile_module() {
  // Hold binary codes.
  llvm::SmallVector<char, 0> obj_buffer;

  {
    llvm::raw_svector_ostream obj_stream(obj_buffer);

    llvm::legacy::PassManager pm;
    llvm::MCContext *ctx;

    if (_compiler->target_machine()->addPassesToEmitMC(pm, ctx, obj_stream)) {
      JeandleCompilation::report_jeandle_error("target does not support MC emission");
      return;
    }

    pm.run(*_llvm_module);
  }

  auto object = std::make_unique<llvm::SmallVectorMemoryBuffer>(std::move(obj_buffer),
                                                                _llvm_module->getModuleIdentifier(),
                                                                false);
  _code.install_obj(std::move(object));
}

static std::string construct_dump_path(const std::string& method_name,
                                       const std::string& timestamp,
                                       const std::string& suffix) {
  assert(suffix == ".ll" || suffix == "-optimized.ll" || suffix == ".o", "invalid suffix for dump file of Jeandle compiler");
  std::string dump_dir = JeandleDumpDirectory ? std::string(JeandleDumpDirectory) : std::string("./");

  // Full name.
  std::string file_name = dump_dir + '/' + method_name + '-' + timestamp + suffix;

  // Normalize the path and remove redundant separators.
  std::filesystem::path clean_path(std::move(file_name));

  return clean_path.lexically_normal().string();
}

void JeandleCompilation::dump_obj() {
  std::string dump_path = construct_dump_path(FuncSigAnalyze::method_name(_method),
                                              _comp_start_time,
                                              ".o");

  std::error_code err_code;
  llvm::raw_fd_ostream dump_stream(dump_path, err_code);
  if (err_code) {
    log_warning(jit, dump)("Could not open file: %s, %s\n",
                           dump_path.c_str() ,err_code.message().c_str());
    return;
  }

  dump_stream.write(_code.object_start(), _code.object_size());
}

void JeandleCompilation::dump_ir(bool optimized) {
  std::string dump_path = construct_dump_path(FuncSigAnalyze::method_name(_method),
                                              _comp_start_time,
                                              optimized ? "-optimized.ll" : ".ll");

  std::error_code err_code;
  llvm::raw_fd_ostream dump_stream(dump_path, err_code, llvm::sys::fs::OF_TextWithCRLF);

  if (err_code) {
    log_warning(jit, dump)("Could not open file: %s, %s\n",
                           dump_path.c_str(),
                           err_code.message().c_str());
    return;
  }

  _llvm_module->print(dump_stream, nullptr);
}


llvm::Function* FuncSigAnalyze::get(ciMethod* method, llvm::Module& module) {
  llvm::SmallVector<llvm::Type*> args;
  llvm::LLVMContext &context = module.getContext();

  // Reciever is the first argument.
  if (!method->is_static()) {
    args.push_back(JeandleType::java2llvm(BasicType::T_OBJECT, context));
  }

  ciSignature* sig = method->signature();
  for (int i = 0; i < sig->count(); i++) {
    args.push_back(JeandleType::java2llvm(sig->type_at(i)->basic_type(), context));
  }

  llvm::FunctionType* func_type =
      llvm::FunctionType::get(JeandleType::java2llvm(sig->return_type()->basic_type(), context),
                              args,
                              false);
  llvm::Function* func = llvm::Function::Create(func_type,
                                                llvm::GlobalValue::ExternalLinkage,
                                                method_name(method),
                                                module);

  setup_description(func);

  func->addFnAttr(llvm::Attribute::get(context, llvm::jeandle::attr::JavaMethod));

  return func;
}

std::string FuncSigAnalyze::method_name(ciMethod* method) {
  std::string class_name = std::string(method->holder()->name()->as_utf8());
  std::replace(class_name.begin(), class_name.end(), '/', '_');

  std::string method_name = std::string(method->name()->as_utf8());
  std::replace(method_name.begin(), method_name.end(), '/', '_');

  std::string sig_name = std::string(method->signature()->as_symbol()->as_utf8());
  std::replace(sig_name.begin(), sig_name.end(), '/', '_');

  return class_name
         + "_" + method_name
         + "_" + sig_name;
}

void FuncSigAnalyze::setup_description(llvm::Function* func) {
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  func->setGC(llvm::jeandle::JeandleGC);

  if (UseCompressedOops) {
    func->addFnAttr(llvm::Attribute::get(func->getContext(), llvm::jeandle::attr::UseCompressedOops));
  }
}
