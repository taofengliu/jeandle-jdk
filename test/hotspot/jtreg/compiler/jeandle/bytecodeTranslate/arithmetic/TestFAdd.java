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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestFAdd::fadd
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestFAdd
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestFAdd {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(3.0f, fadd(1.0f, 2.0f));

        // Rule: If either value1 or value2 is NaN, the result is NaN.
        Asserts.assertEquals(Float.NaN, fadd(Float.NaN, 2.0f));
        Asserts.assertEquals(Float.NaN, fadd(2.0f, Float.NaN));

        // Rule: The sum of two infinities of opposite sign is NaN.
        Asserts.assertEquals(Float.NaN, fadd(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));

        // Rule: The sum of two infinities of the same sign is the infinity of that sign.
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fadd(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fadd(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY));

        // Rule: The sum of an infinity and any finite value is equal to the infinity.
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fadd(Float.POSITIVE_INFINITY, 1.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fadd(Float.POSITIVE_INFINITY, -1.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fadd(Float.NEGATIVE_INFINITY, -1.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fadd(Float.NEGATIVE_INFINITY, 1.0f));

        // Rule: The sum of two zeroes of opposite sign is positive zero.
        Asserts.assertEquals(0.0f, fadd(-0.0f, 0.0f));

        // Rule: The sum of two zeroes of the same sign is the zero of that sign.
        Asserts.assertEquals(0.0f, fadd(0.0f, 0.0f)); // Positive zero
        Asserts.assertEquals(-0.0f, fadd(-0.0f, -0.0f)); // Negative zero

        // Rule: The sum of a zero and a nonzero finite value is equal to the nonzero value.
        Asserts.assertEquals(5.0f, fadd(0.0f, 5.0f));
        Asserts.assertEquals(-5.0f, fadd(-0.0f, -5.0f));

        // Rule: The sum of two nonzero finite values of the same magnitude and opposite sign is positive zero.
        Asserts.assertEquals(0.0f, fadd(5.0f, -5.0f));

        // Rule: Normal addition with rounding and overflow/underflow handling.
        Asserts.assertEquals(3.0f, fadd(1.0f, 2.0f)); // Normal addition
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fadd(Float.MAX_VALUE, Float.MAX_VALUE)); // Overflow
        Asserts.assertEquals(0.0f, fadd(Float.MIN_VALUE / 2, Float.MIN_VALUE / 2)); // Underflow
    }

    public static float fadd(float a, float b) {
        return a + b;
    }

}
