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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestFMul::fmul
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestFMul
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestFMul {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(3.0f, fmul(1.5f, 2.0f));

        // Rule: If either value1 or value2 is NaN, the result is NaN.
        Asserts.assertEquals(Float.NaN, fmul(Float.NaN, 2.0f));
        Asserts.assertEquals(Float.NaN, fmul(2.0f, Float.NaN));

        // Rule: The sign of the result is positive if both values have the same sign,
        // and negative if the values have different signs.
        Asserts.assertEquals(6.0f, fmul(2.0f, 3.0f)); // Positive * Positive = Positive
        Asserts.assertEquals(-6.0f, fmul(-2.0f, 3.0f)); // Negative * Positive = Negative
        Asserts.assertEquals(-6.0f, fmul(2.0f, -3.0f)); // Positive * Negative = Negative
        Asserts.assertEquals(6.0f, fmul(-2.0f, -3.0f)); // Negative * Negative = Positive

        // Rule: Multiplication of an infinity by a zero results in NaN.
        Asserts.assertEquals(Float.NaN, fmul(Float.POSITIVE_INFINITY, 0.0f));
        Asserts.assertEquals(Float.NaN, fmul(Float.NEGATIVE_INFINITY, 0.0f));

        // Rule: Multiplication of an infinity by a finite value results in a signed infinity.
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fmul(Float.POSITIVE_INFINITY, 2.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fmul(Float.NEGATIVE_INFINITY, 2.0f));
        Asserts.assertEquals(Float.NEGATIVE_INFINITY, fmul(Float.POSITIVE_INFINITY, -2.0f));
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fmul(Float.NEGATIVE_INFINITY, -2.0f));

        // Rule: Normal multiplication with rounding and overflow/underflow handling.
        Asserts.assertEquals(6.0f, fmul(2.0f, 3.0f)); // Normal multiplication
        Asserts.assertEquals(Float.POSITIVE_INFINITY, fmul(Float.MAX_VALUE, 2.0f)); // Overflow
        Asserts.assertEquals(0.0f, fmul(Float.MIN_VALUE, Float.MIN_VALUE)); // Underflow
    }

    public static float fmul(float a, float b) {
        return a * b;
    }

}
