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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Jeandle/VMCallback.h"
#include "llvm/IR/Jeandle/VMCallbackLog.h"

#include "jeandle/jeandleVMCallback.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "oops/fieldInfo.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/typeArrayKlass.hpp"
#include "runtime/globals.hpp"
#include "runtime/handles.inline.hpp"
#include "utilities/globalDefinitions.hpp"

namespace {

bool jeandle_is_subtype(uintptr_t sub_klass, uintptr_t super_klass) {
  return ((Klass*)sub_klass)->is_subtype_of((Klass*)super_klass);
}

uintptr_t jeandle_get_common_super_klass(uintptr_t k1, uintptr_t k2) {
  Klass* lca = ((Klass*)k1)->LCA((Klass*)k2);
  return (uintptr_t)lca;
}

uintptr_t jeandle_get_field_type(uintptr_t klass_ptr, int offset) {
  Klass* klass = (Klass*)klass_ptr;
  if (!klass->is_instance_klass()) return 0;

  InstanceKlass* ik = InstanceKlass::cast(klass);
  for (JavaFieldStream fs(ik); !fs.done(); fs.next()) {
    if (fs.offset() == offset) {
      Symbol* sig = fs.signature();
      if (sig->char_at(0) == JVM_SIGNATURE_CLASS ||
          sig->char_at(0) == JVM_SIGNATURE_ARRAY) {
        Thread* current = Thread::current();
        HandleMark hm(current);
        Klass* field_klass = SystemDictionary::find_instance_or_array_klass(
            current, sig, Handle(current, ik->class_loader()),
            Handle(current, ik->protection_domain()));
        return (uintptr_t)field_klass; // 0 if not loaded
      }
      return 0; // primitive field
    }
  }
  return 0; // field not found at offset
}

bool jeandle_is_interface(uintptr_t klass_ptr) {
  return ((Klass*)klass_ptr)->is_interface();
}

bool jeandle_is_object_klass(uintptr_t klass_ptr) {
  return (Klass*)klass_ptr == vmClasses::Object_klass();
}

// Returns 1 iff the target runtime requires strict monitor-stack nesting
// (HotSpot's lightweight locking mode). PEA uses this to decide whether to
// cascade-materialize still-locked virtual objects at a materialization
// point. Mirrors Graal's PlatformConfigurationProvider.requiresStrictLockOrder.
int jeandle_requires_strict_lock_order() {
  return LockingMode == LM_LIGHTWEIGHT ? 1 : 0;
}

// Map HotSpot BasicType to the JBasicType enum used on the LLVM side
// (Boolean=0..Object=8, Count=9). Returns Count as the "no element type"
// sentinel for primitives we don't model or unknown inputs.
static int jeandle_basictype_to_jbasictype(BasicType bt) {
  switch (bt) {
    case T_BOOLEAN: return 0;
    case T_BYTE:    return 1;
    case T_CHAR:    return 2;
    case T_SHORT:   return 3;
    case T_INT:     return 4;
    case T_LONG:    return 5;
    case T_FLOAT:   return 6;
    case T_DOUBLE:  return 7;
    case T_OBJECT:
    case T_ARRAY:   return 8;
    default:        return 9; // JBasicType::Count
  }
}

// Returns the element basic type of an array klass, encoded as the
// LLVM-side JBasicType integer. Returns 9 (Count) for non-array klasses or
// null/unknown inputs.
int jeandle_element_basictype_of_array_klass(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 9;
  Klass* k = (Klass*)klass_ptr;
  if (k->is_typeArray_klass()) {
    return jeandle_basictype_to_jbasictype(
        TypeArrayKlass::cast(k)->element_type());
  }
  if (k->is_objArray_klass()) {
    return 8; // JBasicType::Object
  }
  return 9;
}

// Returns the element klass of an object-array klass. Returns 0 (the
// "no klass / primitive array" sentinel) for typeArrayKlass, null inputs,
// or anything else.
uintptr_t jeandle_array_element_klass(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 0;
  Klass* k = (Klass*)klass_ptr;
  if (k->is_objArray_klass()) {
    return (uintptr_t)ObjArrayKlass::cast(k)->element_klass();
  }
  return 0;
}

// Returns true iff the klass is annotated with @jdk.internal.ValueBased
// (HotSpot's access_flags().is_value_based_class()). Used by PEA to decide
// whether a virtual passing through jeandle.check_if_value_based must be
// force-materialized (so the runtime warning fires on a real oop). Returns
// false defensively for null inputs.
bool jeandle_is_value_based(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  return ((Klass*)klass_ptr)->access_flags().is_value_based_class();
}

// Returns the JBasicType integer of the boxed primitive if klass is one of
// the eight java.lang autobox wrapper classes (Boolean, Byte, Character,
// Short, Integer, Long, Float, Double); returns 9 (JBasicType::Count) for
// any other klass (or null input). Used by PEA B10 to recognise virtual
// Instance VOs that wrap a primitive so the icmp eq fold can perform a
// structural value comparison without depending on object identity.
//
// We use the VM classes (vmClasses::Integer_klass(), etc.) directly. These
// are guaranteed to be loaded at compiler-init time (boxing klasses are
// preloaded as core JDK classes), so a pointer compare is sufficient — no
// load barriers or null guards beyond the entry guard.
int jeandle_is_boxed(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 9; // JBasicType::Count sentinel
  Klass* k = (Klass*)klass_ptr;
  // Pointer-compare against each box klass. The order matches JBasicType
  // (Boolean=0..Double=7) so the return value is the JBasicType integer.
  if (k == vmClasses::Boolean_klass())   return 0;
  if (k == vmClasses::Byte_klass())      return 1;
  if (k == vmClasses::Character_klass()) return 2;
  if (k == vmClasses::Short_klass())     return 3;
  if (k == vmClasses::Integer_klass())   return 4;
  if (k == vmClasses::Long_klass())      return 5;
  if (k == vmClasses::Float_klass())     return 6;
  if (k == vmClasses::Double_klass())    return 7;
  return 9; // JBasicType::Count — not a boxed primitive klass.
}

bool jeandle_is_effectively_final(uintptr_t klass_ptr) {
  Klass* klass = (Klass*)klass_ptr;
  if (klass->is_instance_klass())
    return InstanceKlass::cast(klass)->is_final();
  if (klass->is_typeArray_klass())
    return true;
  if (klass->is_objArray_klass())
    return jeandle_is_effectively_final(
        (uintptr_t)ObjArrayKlass::cast(klass)->bottom_klass());
  return false;
}

// returns true iff the klass declares (or inherits) a non-trivial
// finalize() override. PEA refuses to virtualize allocations of such
// classes because HotSpot must register the finalizer at the original
// allocation site; eliding the alloc would skip that registration and
// break finalize() semantics. Mirrors Graal's NewInstanceNode +
// RegisterFinalizerNode interaction. For null klass inputs we return
// false defensively (PEA will already short-circuit on a missing klass,
// but extra safety is cheap).
bool jeandle_has_finalizer(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  Klass* k = (Klass*)klass_ptr;
  if (!k->is_instance_klass()) return false;
  return InstanceKlass::cast(k)->has_finalizer();
}

// returns true iff the klass is safe to virtualize. Mirrors
// Graal's MetaAccessExtensionProvider.canVirtualize: identity-sensitive
// classes (java.lang.ref.Reference subtypes, java.lang.Thread subtypes,
// and any other class whose lifecycle is observable through global
// runtime state) cannot have their allocations elided because the
// runtime mechanism (reference-queue enqueue, thread-list registration,
// etc.) keys off the actual object identity. Everything else returns
// true. For null klass inputs we return false defensively.
bool jeandle_can_virtualize(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  Klass* k = (Klass*)klass_ptr;
  // Reference subtypes (SoftReference, WeakReference, PhantomReference,
  // FinalReference, plus user subclasses): the GC tracks these via the
  // pending-reference list; eliding an allocation would never produce
  // an oop the discovery code can enqueue. Use is_subclass_of which
  // walks the inheritance chain (Reference itself counts).
  Klass* ref_klass = vmClasses::Reference_klass();
  if (ref_klass != nullptr && k->is_subtype_of(ref_klass)) return false;
  // Thread (and any subtype): the runtime registers Thread instances on
  // the global thread list at construction; the identity is observable
  // through Thread.currentThread() and through the thread group.
  Klass* thread_klass = vmClasses::Thread_klass();
  if (thread_klass != nullptr && k->is_subtype_of(thread_klass)) return false;
  return true;
}

} // anonymous namespace

void register_jeandle_vm_callbacks() {
  llvm::jeandle::VMCallbacks callbacks;
  callbacks.IsSubtype = &jeandle_is_subtype;
  callbacks.GetCommonSuperKlass = &jeandle_get_common_super_klass;
  callbacks.GetFieldType = &jeandle_get_field_type;
  callbacks.IsInterface = &jeandle_is_interface;
  callbacks.IsObjectKlass = &jeandle_is_object_klass;
  callbacks.IsEffectivelyFinal = &jeandle_is_effectively_final;
  callbacks.RequiresStrictLockOrder = &jeandle_requires_strict_lock_order;
  callbacks.ElementBasicTypeOfArrayKlass = &jeandle_element_basictype_of_array_klass;
  callbacks.ArrayElementKlass = &jeandle_array_element_klass;
  callbacks.IsValueBased = &jeandle_is_value_based;
  callbacks.IsBoxed = &jeandle_is_boxed;
  callbacks.HasFinalizer = &jeandle_has_finalizer;
  callbacks.CanVirtualize = &jeandle_can_virtualize;
  llvm::jeandle::registerVMCallbacks(callbacks);

  if (JeandleRecordVMCallbacks) {
    llvm::jeandle::enableVMCallbackRecording();
  }
}
