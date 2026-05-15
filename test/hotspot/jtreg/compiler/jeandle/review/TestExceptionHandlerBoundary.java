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
 * @summary Test exception handler table boundary conditions.
 *          Bug: find_handler uses exclusive-start, inclusive-end (>, <=)
 *          instead of inclusive-start, exclusive-end (>=, <).
 *          This causes: (1) exception at start_pc not caught, (2) exception
 *          at end_pc incorrectly caught.
 * @library /test/lib /
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestExceptionHandlerBoundary::test*
 *      -XX:+UseJeandleCompiler TestExceptionHandlerBoundary
 */

public class TestExceptionHandlerBoundary {

    // Test that an exception at the first instruction of a try block is caught.
    // If find_handler uses ">" instead of ">=", this exception would be missed.
    public static int testStartBoundary() {
        int result = 0;
        try {
            // The very first instruction in the try block
            result = 1 / 0;  // ArithmeticException at start of try
        } catch (ArithmeticException e) {
            return 42;  // Should catch this
        }
        return result;
    }

    // Test with multiple catch blocks
    public static int testMultipleCatchBlocks() {
        try {
            int x = 1 / 0;
            return x;
        } catch (ArithmeticException e) {
            return 1;
        } catch (Exception e) {
            return 2;
        }
    }

    // Test nested try-catch
    public static int testNestedTryCatch() {
        try {
            try {
                int x = 1 / 0;
            } catch (ArithmeticException e) {
                return 1;
            }
        } catch (Exception e) {
            return 2;
        }
        return 0;
    }

    // Test exception in a loop inside try block
    public static int testExceptionInLoop() {
        try {
            for (int i = 0; i < 10; i++) {
                if (i == 5) {
                    throw new RuntimeException("loop");
                }
            }
            return 0;
        } catch (RuntimeException e) {
            return 5;
        }
    }

    public static void main(String[] args) {
        int r1 = testStartBoundary();
        if (r1 != 42) {
            throw new RuntimeException("testStartBoundary failed: expected 42, got " + r1
                + " (exception at start_pc not caught - off-by-one bug)");
        }

        int r3 = testMultipleCatchBlocks();
        if (r3 != 1) {
            throw new RuntimeException("testMultipleCatchBlocks failed: expected 1, got " + r3);
        }

        int r4 = testNestedTryCatch();
        if (r4 != 1) {
            throw new RuntimeException("testNestedTryCatch failed: expected 1, got " + r4);
        }

        int r5 = testExceptionInLoop();
        if (r5 != 5) {
            throw new RuntimeException("testExceptionInLoop failed: expected 5, got " + r5);
        }

        System.out.println("All tests passed");
    }
}