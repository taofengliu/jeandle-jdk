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
 * @summary Test that allocation failure properly dispatches exceptions.
 *          Bug: Missing deoptimize_caller_frame in allocation routines.
 *          When an allocation slow path throws (e.g., InstantiationException),
 *          the caller frame should be deoptimized so exception dispatch
 *          uses the interpreter's handler table.
 * @library /test/lib /
 * @run main/othervm -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestMissingDeoptCallerFrame::test*
 *      -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 TestMissingDeoptCallerFrame
 */

public class TestMissingDeoptCallerFrame {

    // Test that InstantiationException from newInstance() is properly dispatched
    public static String testNewInstanceException() {
        try {
            // This will throw InstantiationException because
            // AbstractClass is abstract and cannot be instantiated
            AbstractClass.class.newInstance();
            return "no exception";
        } catch (InstantiationException e) {
            return "caught InstantiationException";
        } catch (IllegalAccessException e) {
            return "caught IllegalAccessException";
        }
    }

    // Test that exception from object allocation with try/catch in compiled code
    public static String testObjectAllocationWithCatch() {
        try {
            Object obj = new Object();
            if (obj == null) {
                return "null";
            }
            return "allocated";
        } catch (Exception e) {
            return "caught exception";
        }
    }

    // Test new array with exception handler
    public static int testNewArrayWithCatch(int size) {
        try {
            int[] arr = new int[size];
            return arr.length;
        } catch (NegativeArraySizeException e) {
            return -1;
        }
    }

    abstract static class AbstractClass {
        abstract void foo();
    }

    public static void main(String[] args) {
        // Warm up the methods to get them compiled
        for (int i = 0; i < 200; i++) {
            testObjectAllocationWithCatch();
            testNewArrayWithCatch(10);
        }

        String r1 = testNewInstanceException();
        if (!r1.equals("caught InstantiationException") && !r1.equals("caught IllegalAccessException")) {
            throw new RuntimeException("testNewInstanceException failed: " + r1);
        }

        String r3 = testObjectAllocationWithCatch();
        if (!r3.equals("allocated")) {
            throw new RuntimeException("testObjectAllocationWithCatch failed: " + r3);
        }

        int r4 = testNewArrayWithCatch(-1);
        if (r4 != -1) {
            throw new RuntimeException("testNewArrayWithCatch(-1) failed: expected -1, got " + r4
                + " (NegativeArraySizeException not properly dispatched)");
        }

        int r5 = testNewArrayWithCatch(100);
        if (r5 != 100) {
            throw new RuntimeException("testNewArrayWithCatch(100) failed: expected 100, got " + r5);
        }

        System.out.println("All tests passed");
    }
}