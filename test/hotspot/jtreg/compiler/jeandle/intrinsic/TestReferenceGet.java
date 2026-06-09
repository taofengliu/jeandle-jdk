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
 *
 */

/*
 * @test
 * @summary Verify Reference.get() intrinsic emits correct memory ordering:
 *          Unordered atomic load + CPUOrder fence + G1 SATB barrier
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler -XX:+UseG1GC
 *      -XX:+JeandleDumpIR
 *      -XX:CompileCommand=compileonly,*TestReferenceGet*::test*
 *      compiler.jeandle.intrinsic.TestReferenceGet
 */

package compiler.jeandle.intrinsic;

import jdk.test.lib.Asserts;
import java.lang.ref.WeakReference;
import compiler.jeandle.fileCheck.FileCheck;

public class TestReferenceGet {

    static class Holder {
        Object value;
        Holder(Object v) { value = v; }
    }

    private static Object testReferenceGet(WeakReference<Object> ref) {
        // This call will be intrinsified to jeandle.reference_get
        return ref.get();
    }

    public static void main(String[] args) throws Exception {
        // Runtime correctness: verify get() returns expected values
        Object obj = new Object();
        WeakReference<Object> ref = new WeakReference<>(obj);
        Asserts.assertEquals(testReferenceGet(ref), obj, "get() should return the referent");

        Holder holder = new Holder(new Object());
        WeakReference<Object> ref2 = new WeakReference<>(holder.value);
        Asserts.assertEquals(testReferenceGet(ref2), holder.value, "get() should return the referent");

        // IR verification: check the jeandle.reference_get JavaOp
        String currentDir = System.getProperty("user.dir");
        FileCheck fc = new FileCheck(currentDir,
                TestReferenceGet.class.getDeclaredMethod("testReferenceGet", WeakReference.class),
                false);

        // Verify the jeandle.reference_get function definition is present.
        // IR order: load -> barrier_call -> fence -> ret
        fc.checkPattern("define.*jeandle.reference_get");

        // Verify the load is unordered atomic (appears first in the function)
        fc.checkPattern("load atomic ptr addrspace\\(1\\).*unordered");

        // Verify G1 pre-barrier call is present (under G1GC, appears after the load)
        fc.checkPattern("call.*jeandle.g1_pre_barrier_loaded");

        // Verify CPUOrder fence is present after the barrier call
        fc.check("fence syncscope(\"singlethread\") seq_cst");
    }
}