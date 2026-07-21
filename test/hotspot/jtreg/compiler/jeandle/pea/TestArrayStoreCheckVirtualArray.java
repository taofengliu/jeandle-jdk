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
 * @summary PEA array-store-check folding (foldArrayStoreCheck) across the
 *          §4.2 matrix: compatible String/null into a virtual String[]
 *          (check elided, array virtualized), primitive int[] (no covariant
 *          check), interface Runnable[] with a provably-compatible value,
 *          an incompatible store that must keep the check and throw ASE, and a
 *          store into an already-escaped (materialized) array. Behavior and
 *          per-method IR shape are both asserted.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestArrayStoreCheckVirtualArray
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestArrayStoreCheckVirtualArray {
    static final String WRAPPER =
            "compiler.jeandle.pea.TestArrayStoreCheckVirtualArray$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method mBase = TestWrapper.class.getMethod("test");
        Method mNull = TestWrapper.class.getMethod("testCompatibleWithNull");
        Method mPrim = TestWrapper.class.getMethod("testPrimitiveArray");
        Method mIface = TestWrapper.class.getMethod("testInterfaceCompatible");
        Method mAse = TestWrapper.class.getMethod("testIncompatibleASE");
        Method mMat = TestWrapper.class.getMethod("testMaterializedArray");
        Method[] all = {mBase, mNull, mPrim, mIface, mAse, mMat};

        PEATestUtils.Run run = PEATestUtils.run(WRAPPER)
                .llvmOptions(PEATestUtils.peaLLVMOptionsClass(TestWrapper.class));
        for (Method m : all) {
            run.compileonly(m.getName());
        }
        OutputAnalyzer out = run.run();

        // NeverEscape + store-check elided. The @jeandle.new_(array) is present
        // before PEA and eliminated after, and the @jeandle.array_store_check
        // JavaOp (the PEA target) is gone from the post-PEA body. VO counts:
        // testInterfaceCompatible has two (the Impl[] array + the Impl element).
        PEATestUtils.assertStats(out, mBase, 1, 0, 0);
        PEATestUtils.assertStats(out, mNull, 1, 0, 0);
        PEATestUtils.assertStats(out, mPrim, 1, 0, 0);
        PEATestUtils.assertStats(out, mIface, 2, 0, 0);
        for (Method m : new Method[] {mBase, mNull, mPrim, mIface}) {
            PEATestUtils.assertNeverEscapes(out, m);
            PEATestUtils.bodyAfterPEA(out, m).assertAbsent("jeandle.array_store_check");
        }
        // Check survives + allocation retained (materialized). Assert via the
        // PEA target: @jeandle.new_ allocations are retained after PEA, and the
        // @jeandle.array_store_check JavaOp survives PEA (pre-lowering) instead
        // of being folded — proving the check was kept, not eliminated.
        PEATestUtils.assertAllocRetained(out, mAse, 2);   // String[] array + Box value
        PEATestUtils.assertAllocRetained(out, mMat, 1);   // published String[] array
        for (Method m : new Method[] {mAse, mMat}) {
            PEATestUtils.bodyAfterPEA(out, m).assertPresent("jeandle.array_store_check");
        }

        out.shouldContain("baseline: ok");
        out.shouldContain("nullStore: ok");
        out.shouldContain("primitive: ok");
        out.shouldContain("interface: ok");
        out.shouldContain("ase: ok");
        out.shouldContain("materialized: ok");
    }

    public static class TestWrapper {
        public interface Component { void run(); }
        public static class Impl implements Component { public void run() {} }
        public static class Box { public int x; } // a non-String, non-Object allocation

        static String[] published; // sink that forces an array to materialize

        public static void main(String[] args) {
            new Impl(); // class-init
            new Box(); // resolve Box so testIncompatibleASE's `new Box()` is resolvable at compile time
            Asserts.assertEquals(test(), 2);
            System.out.println("baseline: ok");
            Asserts.assertEquals(testCompatibleWithNull(), 4);
            System.out.println("nullStore: ok");
            Asserts.assertEquals(testPrimitiveArray(), 6);
            System.out.println("primitive: ok");
            Asserts.assertTrue(testInterfaceCompatible());
            System.out.println("interface: ok");
            try {
                testIncompatibleASE();
                throw new AssertionError("expected ArrayStoreException");
            } catch (java.lang.ArrayStoreException e) {
                System.out.println("ase: ok");
            }
            Asserts.assertEquals(testMaterializedArray(), 3);
            System.out.println("materialized: ok");
        }

        // Baseline: String[] with provably-compatible String stores.
        public static int test() {
            String[] arr = new String[2];
            arr[0] = "a";
            arr[1] = "b";
            return arr[0].length() + arr[1].length();
        }

        // Compatible String and null stores into a virtual String[].
        public static int testCompatibleWithNull() {
            String[] arr = new String[3];
            arr[0] = "a";
            arr[1] = null;
            arr[2] = "bb";
            return arr[0].length() + (arr[1] == null ? 1 : 0) + arr[2].length();
        }

        // Primitive array: no covariant store check at all.
        public static int testPrimitiveArray() {
            int[] arr = new int[3];
            arr[0] = 1;
            arr[1] = 2;
            arr[2] = 3;
            return arr[0] + arr[1] + arr[2];
        }

        // Interface array with a provably-compatible value (Impl implements the
        // component type). The store check folds.
        public static boolean testInterfaceCompatible() {
            Impl[] arr = new Impl[1];
            arr[0] = new Impl();
            return arr[0] != null;
        }

        // Incompatible store: a fresh Box into a String[] (held by an
        // Object[]-typed local). PEA must NOT virtualize the array (the store
        // check survives), so the compiled code throws ArrayStoreException.
        // No local catch: the exception unwinds to the caller.
        public static int testIncompatibleASE() {
            Object[] arr = new String[2];   // static Object[], runtime String[]
            arr[0] = "keep";                 // compatible; replayed before the failing check
            Box o = new Box();               // exact klass Box, incompatible with String
            arr[1] = o;                      // throws ArrayStoreException
            return 0;                        // unreachable
        }

        // Already-escaped (materialized) array: the check must survive.
        public static int testMaterializedArray() {
            String[] arr = new String[2];
            published = arr;            // escapes globally -> materialized
            arr[0] = "x";
            arr[1] = "yy";
            return published[0].length() + published[1].length();
        }
    }
}
