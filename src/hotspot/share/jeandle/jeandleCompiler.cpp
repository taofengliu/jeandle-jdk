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

#include "llvm/Bitcode/BitcodeWriter.h"
#include "llvm/IRReader/IRReader.h"
#include "llvm/MC/TargetRegistry.h"
#include "llvm/Support/SmallVectorMemoryBuffer.h"
#include "llvm/Support/SourceMgr.h"
#include "llvm/TargetParser/Host.h"

#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiler.hpp"
#include "jeandle/jeandleType.hpp"
#include "runtime/arguments.hpp"

JeandleCompiler::JeandleCompiler(llvm::TargetMachine* target_machine) :
                                 AbstractCompiler(compiler_jeandle),
                                 _target_machine(target_machine),
                                 _data_layout(target_machine->createDataLayout()),
                                 _template_buffer(nullptr) {}

JeandleCompiler* JeandleCompiler::create() {
  llvm::Triple target_triple = llvm::Triple(llvm::sys::getProcessTriple());

  std::string err_msg;
  const llvm::Target* target = llvm::TargetRegistry::lookupTarget(target_triple.getTriple(), err_msg);
  if (!target) {
    fatal("Cannot create LLVM target machine: %s", err_msg.c_str());
  }
  if (!target->hasJIT()) {
    fatal("Target has no JIT support");
  }

  llvm::TargetOptions options;
  llvm::SubtargetFeatures features;
  options.EmitStackSizeSection = true;

  llvm::TargetMachine* target_machine = target->createTargetMachine(target_triple.getTriple(), ""/* CPU */, features.getString(), options,
                                                                    llvm::Reloc::Model::Static, llvm::CodeModel::Model::Small,
                                                                    llvm::CodeGenOptLevel::Aggressive, true/* JIT */);

  return new JeandleCompiler(target_machine);
}

void JeandleCompiler::initialize() {
  if (should_perform_init()) {
    set_state(initialized);
    initialize_template_buffer();
  }
}

void JeandleCompiler::compile_method(ciEnv* env, ciMethod* target, int entry_bci, bool install_code, DirectiveSet* directive){
  ResourceMark rm;
  JeandleCompilation compilation(this, env, target, entry_bci, install_code, _template_buffer.get());
}

void JeandleCompiler::print_timers() {
  return;
}

void JeandleCompiler::initialize_template_buffer() {
  llvm::LLVMContext tmp_context;
  llvm::SMDiagnostic error;

  std::unique_ptr<llvm::Module> template_module = llvm::parseIRFile(Arguments::get_jeandle_template_path(), error, tmp_context);
  if (template_module == nullptr) {
    fatal("Failed to parse template module: %s", Arguments::get_jeandle_template_path());
  }

  llvm::SmallVector<char, 0> bitcode_buffer;
  llvm::raw_svector_ostream bitcode_stream(bitcode_buffer);
  llvm::WriteBitcodeToFile(*template_module, bitcode_stream);

  _template_buffer = std::make_unique<llvm::SmallVectorMemoryBuffer>(std::move(bitcode_buffer), "template module", false);
  if (_template_buffer == nullptr) {
    fatal("Failed to initialize template buffer");
  }
}
