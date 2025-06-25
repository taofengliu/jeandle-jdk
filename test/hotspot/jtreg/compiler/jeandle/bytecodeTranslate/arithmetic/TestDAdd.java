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
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestDAdd::dadd
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDAdd
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDAdd {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(3.0d, dadd(1.0d, 2.0d));

        // Rule: If either value1 or value2 is NaN, the result is NaN.
        Asserts.assertEquals(Double.NaN, dadd(Double.NaN, 2.0d));
        Asserts.assertEquals(Double.NaN, dadd(2.0d, Double.NaN));

        // Rule: The sum of two infinities of opposite sign is NaN.
        Asserts.assertEquals(Double.NaN, dadd(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));

        // Rule: The sum of two infinities of the same sign is the infinity of that sign.
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dadd(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dadd(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY));

        // Rule: The sum of an infinity and any finite value is equal to the infinity.
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dadd(Double.POSITIVE_INFINITY, 1.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dadd(Double.POSITIVE_INFINITY, -1.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dadd(Double.NEGATIVE_INFINITY, -1.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dadd(Double.NEGATIVE_INFINITY, 1.0d));

        // Rule: The sum of two zeroes of opposite sign is positive zero.
        Asserts.assertEquals(0.0d, dadd(-0.0d, 0.0d));

        // Rule: The sum of two zeroes of the same sign is the zero of that sign.
        Asserts.assertEquals(0.0d, dadd(0.0d, 0.0d)); // Positive zero
        Asserts.assertEquals(-0.0d, dadd(-0.0d, -0.0d)); // Negative zero

        // Rule: The sum of a zero and a nonzero finite value is equal to the nonzero value.
        Asserts.assertEquals(5.0d, dadd(0.0d, 5.0d));
        Asserts.assertEquals(-5.0d, dadd(-0.0d, -5.0d));

        // Rule: The sum of two nonzero finite values of the same magnitude and opposite sign is positive zero.
        Asserts.assertEquals(0.0d, dadd(5.0d, -5.0d));

        // Rule: Normal addition with rounding and overflow/underflow handling.
        Asserts.assertEquals(3.0d, dadd(1.0d, 2.0d)); // Normal addition
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dadd(Double.MAX_VALUE, Double.MAX_VALUE)); // Overflow
        Asserts.assertEquals(0.0d, dadd(Double.MIN_VALUE / 2, Double.MIN_VALUE / 2)); // Underflow
    }

    public static double dadd(double a, double b) {
        return a + b;
    }

}
