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

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @compile Neg.jasm
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestNeg::testIneg
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestNeg::testLneg
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestNeg::testFneg
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestNeg::testDneg
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestNeg
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestNeg {

    public static void main(String[] args) throws Exception {
        testIneg();
        testLneg();
        testFneg();
        testDneg();
    }

    static int testIneg(int in) {
        return -in;
    }

    static void testIneg() {
        int in = 0;
        Asserts.assertEquals(in, 0);
        Asserts.assertEquals(in, testIneg(0));
        Asserts.assertEquals(testIneg(in), 0);

        in = 1;
        Asserts.assertEquals(-in, -1);

        in = -1;
        Asserts.assertEquals(-in, 1);

        Asserts.assertEquals(-1, testIneg(1));
        Asserts.assertEquals(Integer.MAX_VALUE * (-1), testIneg(Integer.MAX_VALUE));
        Asserts.assertEquals(Integer.MIN_VALUE * (-1), testIneg(Integer.MIN_VALUE));

        Asserts.assertEquals(-128, testIneg(128));
        Asserts.assertEquals(-5000, testIneg(5000));
    }

    static long testLneg(long in) {
        return -in;
    }

    static void testLneg() {
        long in = 0;
        Asserts.assertEquals(in, 0l);
        Asserts.assertEquals(in, testLneg(0));
        Asserts.assertEquals(testLneg(in), 0l);

        in = 1;
        Asserts.assertEquals(-in, -1l);

        in = -1;
        Asserts.assertEquals(-in, 1l);

        Asserts.assertEquals(-1l, testLneg(1));
        Asserts.assertEquals(Long.MAX_VALUE * (-1), testLneg(Long.MAX_VALUE));
        Asserts.assertEquals(Long.MIN_VALUE * (-1), testLneg(Long.MIN_VALUE));

        Asserts.assertEquals(-128l, testLneg(128));
        Asserts.assertEquals(-5000l, testLneg(5000));
    }

    static float testFneg(float in) {
        return -in;
    }

    static void testFneg() {
        float input = 123.45f;
        float expected = -123.45f;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = -678.90f;
        expected = 678.90f;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = 0.0f;
        expected = -0.0f;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = -0.0f;
        expected = 0.0f;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = Float.POSITIVE_INFINITY;
        expected = Float.NEGATIVE_INFINITY;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = Float.NEGATIVE_INFINITY;
        expected = Float.POSITIVE_INFINITY;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = Float.NaN;
        expected = Float.NaN;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = Float.MIN_VALUE;
        expected = -Float.MIN_VALUE;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));

        input = Float.MAX_VALUE;
        expected = -Float.MAX_VALUE;
        Asserts.assertEquals(expected, TestNeg.testFneg(input));
    }

    static double testDneg(double in) {
        return -in;
    }

    static void testDneg() {
        double input = 123.45d;
        double expected = -123.45d;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = -678.90d;
        expected = 678.90d;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = 0.0d;
        expected = -0.0d;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = -0.0d;
        expected = 0.0d;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = Double.POSITIVE_INFINITY;
        expected = Double.NEGATIVE_INFINITY;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = Double.NEGATIVE_INFINITY;
        expected = Double.POSITIVE_INFINITY;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = Double.NaN;
        expected = Double.NaN;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = Double.MIN_VALUE;
        expected = -Double.MIN_VALUE;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));

        input = Double.MAX_VALUE;
        expected = -Double.MAX_VALUE;
        Asserts.assertEquals(expected, TestNeg.testDneg(input));
    }

}
