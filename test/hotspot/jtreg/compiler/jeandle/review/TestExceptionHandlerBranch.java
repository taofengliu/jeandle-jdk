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
 * @summary Test exception handler reachable via if_zero/if_acmp/if_null branches.
 *          Bug: if_zero, if_acmp, if_null do not call merge_into_exception_handler()
 *          for their successor blocks, causing exception handlers to have
 *          uninitialized VM state and be treated as dead code.
 * @library /test/lib /
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestExceptionHandlerBranch::test*
 *      -XX:+UseJeandleCompiler TestExceptionHandlerBranch
 */

public class TestExceptionHandlerBranch {

    // Exception handler reachable via if_zero branch
    public static int testIfZeroBranch(boolean flag) {
        try {
            if (flag) {
                throw new RuntimeException("test");
            }
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // Exception handler reachable via if_acmp branch
    public static int testIfAcmpBranch(String a, String b) {
        try {
            if (a == b) {
                throw new RuntimeException("test");
            }
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // Exception handler reachable via if_null branch
    public static int testIfNullBranch(Object obj) {
        try {
            if (obj == null) {
                throw new NullPointerException("test");
            }
            return 0;
        } catch (NullPointerException e) {
            return 1;
        }
    }

    // Exception handler reachable via if_icmp branch (this should work)
    public static int testIfIcmpBranch(int a, int b) {
        try {
            if (a == b) {
                throw new RuntimeException("test");
            }
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // Combination: exception handler reachable via multiple branch types
    public static int testCombinedBranches(int mode, Object obj) {
        try {
            if (mode == 1) {
                throw new RuntimeException("mode1");
            }
            if (obj == null) {
                throw new NullPointerException("null");
            }
            if (obj instanceof String) {
                throw new ClassCastException("string");
            }
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    public static void main(String[] args) {
        int r1 = testIfZeroBranch(true);
        if (r1 != 1) {
            throw new RuntimeException("testIfZeroBranch(true) failed: expected 1, got " + r1
                + " (exception handler not reached via if_zero branch)");
        }

        int r2 = testIfZeroBranch(false);
        if (r2 != 0) {
            throw new RuntimeException("testIfZeroBranch(false) failed: expected 0, got " + r2);
        }

        String s = "hello";
        int r3 = testIfAcmpBranch(s, s);
        if (r3 != 1) {
            throw new RuntimeException("testIfAcmpBranch(same) failed: expected 1, got " + r3
                + " (exception handler not reached via if_acmp branch)");
        }

        int r4 = testIfAcmpBranch("a", "b");
        if (r4 != 0) {
            throw new RuntimeException("testIfAcmpBranch(diff) failed: expected 0, got " + r4);
        }

        int r5 = testIfNullBranch(null);
        if (r5 != 1) {
            throw new RuntimeException("testIfNullBranch(null) failed: expected 1, got " + r5
                + " (exception handler not reached via if_null branch)");
        }

        int r6 = testIfNullBranch(new Object());
        if (r6 != 0) {
            throw new RuntimeException("testIfNullBranch(obj) failed: expected 0, got " + r6);
        }

        int r7 = testIfIcmpBranch(1, 1);
        if (r7 != 1) {
            throw new RuntimeException("testIfIcmpBranch(1,1) failed: expected 1, got " + r7);
        }

        int r8 = testCombinedBranches(1, new Object());
        if (r8 != 1) {
            throw new RuntimeException("testCombinedBranches(1) failed: expected 1, got " + r8);
        }

        System.out.println("All tests passed");
    }
}