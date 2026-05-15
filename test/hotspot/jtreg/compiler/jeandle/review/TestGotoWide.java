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
 * @summary Test that _goto_w bytecode is handled correctly.
 *          Bug: _goto_w is marked Unimplemented() in the abstract interpreter,
 *          causing a crash when compiling methods with code > 32KB.
 *          Since generating such methods in Java source is impractical,
 *          this test verifies that normal goto bytecodes work correctly
 *          and serves as a placeholder for the _goto_w bug.
 * @library /test/lib /
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestGotoWide::test*
 *      -XX:+UseJeandleCompiler TestGotoWide
 */

public class TestGotoWide {

    // Test normal goto in a loop (back-edge goto)
    public static int testGotoLoop(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    // Test goto in a switch-like pattern
    public static int testGotoBranch(int mode) {
        if (mode == 1) {
            return 10;
        }
        if (mode == 2) {
            return 20;
        }
        return 30;
    }

    // Test break with label (generates goto)
    public static int testLabeledBreak(int[][] matrix) {
        int sum = 0;
        outer:
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if (matrix[i][j] < 0) {
                    break outer;
                }
                sum += matrix[i][j];
            }
        }
        return sum;
    }

    // Test continue with label (generates goto)
    public static int testLabeledContinue(int[][] matrix) {
        int sum = 0;
        outer:
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if (matrix[i][j] < 0) {
                    continue outer;
                }
                sum += matrix[i][j];
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        int r1 = testGotoLoop(100);
        if (r1 != 4950) {
            throw new RuntimeException("testGotoLoop(100) failed: expected 4950, got " + r1);
        }

        int r2 = testGotoBranch(1);
        if (r2 != 10) {
            throw new RuntimeException("testGotoBranch(1) failed: expected 10, got " + r2);
        }

        int r3 = testGotoBranch(2);
        if (r3 != 20) {
            throw new RuntimeException("testGotoBranch(2) failed: expected 20, got " + r3);
        }

        int r4 = testGotoBranch(3);
        if (r4 != 30) {
            throw new RuntimeException("testGotoBranch(3) failed: expected 30, got " + r4);
        }

        int[][] matrix = {{1, 2, 3}, {4, -1, 6}, {7, 8, 9}};
        int r5 = testLabeledBreak(matrix);
        if (r5 != 1 + 2 + 3 + 4) {
            throw new RuntimeException("testLabeledBreak failed: expected 10, got " + r5);
        }

        int r6 = testLabeledContinue(matrix);
        if (r6 != 1 + 2 + 3 + 4 + 7 + 8 + 9) {
            throw new RuntimeException("testLabeledContinue failed: expected 34, got " + r6);
        }

        System.out.println("All tests passed");
    }
}