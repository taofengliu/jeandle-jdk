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

/**
 * @test
 * @summary Test that null instanceof Object returns false.
 *          Bug: TypeCheckElimination folds instanceof Object to true
 *          without verifying the operand is non-null. If the operand
 *          is null, instanceof should return false, not true.
 * @library /test/lib /
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestInstanceOfNull::test*
 *      -XX:+UseJeandleCompiler TestInstanceOfNull
 */

public class TestInstanceOfNull {

    // Direct null instanceof Object
    public static boolean testNullInstanceOfObject() {
        Object obj = null;
        return obj instanceof Object;
    }

    // Null passed through a method
    public static boolean testNullViaMethod() {
        return getNull() instanceof Object;
    }

    private static Object getNull() {
        return null;
    }

    // Null check in a branch that the optimizer might eliminate
    public static int testNullInBranch(Object obj) {
        if (obj instanceof Object) {
            return 1;
        } else {
            return 0;
        }
    }

    // instanceof with interface
    public static boolean testNullInstanceOfInterface() {
        Runnable r = null;
        return r instanceof Runnable;
    }

    // instanceof with array
    public static boolean testNullInstanceOfArray() {
        int[] arr = null;
        return arr instanceof int[];
    }

    // Checkcast with null should not throw
    public static int testNullCheckcast() {
        Object obj = null;
        String s = (String) obj;
        return s == null ? 1 : 0;
    }

    public static void main(String[] args) {
        boolean r1 = testNullInstanceOfObject();
        if (r1 != false) {
            throw new RuntimeException("testNullInstanceOfObject failed: expected false, got " + r1
                + " (null instanceof Object incorrectly folded to true)");
        }

        boolean r2 = testNullViaMethod();
        if (r2 != false) {
            throw new RuntimeException("testNullViaMethod failed: expected false, got " + r2);
        }

        int r3 = testNullInBranch(null);
        if (r3 != 0) {
            throw new RuntimeException("testNullInBranch(null) failed: expected 0, got " + r3
                + " (null branch incorrectly taken)");
        }

        int r4 = testNullInBranch(new Object());
        if (r4 != 1) {
            throw new RuntimeException("testNullInBranch(obj) failed: expected 1, got " + r4);
        }

        boolean r5 = testNullInstanceOfInterface();
        if (r5 != false) {
            throw new RuntimeException("testNullInstanceOfInterface failed: expected false, got " + r5);
        }

        boolean r6 = testNullInstanceOfArray();
        if (r6 != false) {
            throw new RuntimeException("testNullInstanceOfArray failed: expected false, got " + r6);
        }

        int r7 = testNullCheckcast();
        if (r7 != 1) {
            throw new RuntimeException("testNullCheckcast failed: expected 1, got " + r7);
        }

        System.out.println("All tests passed");
    }
}