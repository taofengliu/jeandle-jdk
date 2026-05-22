/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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

#ifndef SHARE_JEANDLE_TYPE_HPP
#define SHARE_JEANDLE_TYPE_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Type.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciObject.hpp"
#include "ci/compilerInterface.hpp"
#include "jeandle/jeandleCompilation.hpp"

class JeandleType : public AllStatic {
 public:

  // Convert a Java type to its LLVM type.
  static llvm::Type* java2llvm(BasicType jvm_type, llvm::LLVMContext& context);

  static bool is_double_word_type(llvm::Type* t) {
    return t->isIntegerTy(64) || t->isDoubleTy();
  }

  // Get a LLVM constant value according to a Java type.
  // For example: If you want to get a LLVM value that represent a Java int, use int_const().

  static llvm::ConstantInt* int_const(llvm::IRBuilder<>& builder, uint32_t value) {
    return builder.getInt32(value);
  }

  static llvm::ConstantInt* long_const(llvm::IRBuilder<>& builder, uint64_t value) {
    return builder.getInt64(value);
  }

  static llvm::ConstantFP* float_const(llvm::IRBuilder<>& builder, float value) {
    return (llvm::ConstantFP*)llvm::ConstantFP::get(builder.getFloatTy(), value);
  }

  static llvm::ConstantFP* double_const(llvm::IRBuilder<>& builder, double value) {
    return (llvm::ConstantFP*)llvm::ConstantFP::get(builder.getDoubleTy(), value);
  }

  // Convert a Java type to computational type
  // Reference: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html#jvms-2.11.1-320
  static BasicType actual2computational(BasicType bt) {
    switch (bt) {
      case T_BYTE   :
      case T_CHAR   :
      case T_SHORT  :
      case T_BOOLEAN:
      case T_INT    :
        return T_INT;
      case T_VOID   :
      case T_LONG   :
      case T_FLOAT  :
      case T_DOUBLE :
        return bt;
      case T_ARRAY  :
      case T_OBJECT :
        return T_OBJECT;
      case T_ADDRESS:
        return T_ADDRESS;
      default       :
        ShouldNotReachHere();
    }
  }
};

/* A pair of BasicType and llvm::Value* used by JeandleVMState */
class TypedValue {
private:
  BasicType _basic_type;
  llvm::Value * _value;
  // Metadata for LLVM values that are known aliases of a constant oop. It is
  // carried with stack/local values so PHI nodes can preserve it across merges.
  ciObject* _constant_oop;
  // C2 only folds stable array elements when the array value is known to come
  // from an @Stable array field. Track the remaining stable dimensions here.
  int _stable_dimension;

public:
  TypedValue(BasicType type, llvm::Value* value, ciObject* constant_oop = nullptr, int stable_dimension = 0) :
      _basic_type(type), _value(value), _constant_oop(constant_oop), _stable_dimension(stable_dimension) {
    if (value == nullptr) {
      assert(type == T_ILLEGAL, "value is null");
      assert(constant_oop == nullptr, "null value cannot be a constant oop");
      assert(stable_dimension == 0, "null value cannot be a stable array");
    } else {
      assert(value->getType() == JeandleType::java2llvm(type, value->getContext()), "type does not match");
      assert(constant_oop == nullptr || is_reference_type(type), "only reference values can be constant oops");
      assert(stable_dimension >= 0, "stable dimension must be non-negative");
      assert(stable_dimension == 0 || constant_oop != nullptr, "stable arrays must be constant oops");
      assert(stable_dimension == 0 || constant_oop->is_array(), "stable arrays must use array oops");
    }
  }
  TypedValue() : _basic_type(T_ILLEGAL), _value(nullptr), _constant_oop(nullptr), _stable_dimension(0) {}

  static TypedValue null_value() { return TypedValue(T_ILLEGAL, nullptr); }
  bool   is_null() const { return _basic_type == T_ILLEGAL && _value == nullptr; }

  BasicType computational_type() const { return JeandleType::actual2computational(_basic_type); }
  BasicType        actual_type() const { return _basic_type; }
  llvm::Value*           value() const { return _value; }
  ciObject*      constant_oop() const { return _constant_oop; }
  int        stable_dimension() const { return _stable_dimension; }

  TypedValue clone_with_value(llvm::Value* value) const {
    return TypedValue(_basic_type, value, _constant_oop, _stable_dimension);
  }

  void merge_constant_oop(const TypedValue& incoming) {
    if (_constant_oop != nullptr && _constant_oop == incoming._constant_oop) {
      if (_stable_dimension != incoming._stable_dimension) {
        _stable_dimension = 0;
      }
      return;
    }
    _constant_oop = nullptr;
    _stable_dimension = 0;
  }
};

/* A pair of TypedValue and corresponding lock (llvm::Value*) used by monitors */
class LockValue {
private:
  TypedValue _object;
  llvm::Value* _basic_lock;

public:
  LockValue(TypedValue object, llvm::Value* lock) : _object(object), _basic_lock(lock) { }
  LockValue() : _object(TypedValue()), _basic_lock(nullptr) { }

  bool equals(const LockValue& rhs) {
    if (JeandleCompilation::current()->is_osr_compilation()) {
      // During OSR compilation, identical logical monitors may be associated with distinct
      // LLVM SSA values due to state merging from the OSR entry and loop predecessors.
      // Comparing types and basic lock indices ensures semantic equivalence.
      return _object.value()->getType() == rhs._object.value()->getType() && _basic_lock == rhs._basic_lock;
    }
    return _object.value() == rhs._object.value() && _basic_lock == rhs._basic_lock;
  }

  TypedValue    object() const { return _object; }
  llvm::Value*    lock() const { return _basic_lock; }
  bool         is_null() const { return _object.is_null() || _basic_lock == nullptr; }

  void set_object(TypedValue object) { _object = object; }
  void set_lock(llvm::Value* lock) { _basic_lock = lock; }
};

#endif // SHARE_JEANDLE_TYPE_HPP
