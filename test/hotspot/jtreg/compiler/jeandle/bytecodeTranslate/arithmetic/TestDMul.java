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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestDMul::dmul
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDMul
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDMul {

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(3.0d, dmul(1.5d, 2.0d));

        // Rule: If either value1 or value2 is NaN, the result is NaN.
        Asserts.assertEquals(Double.NaN, dmul(Double.NaN, 2.0d));
        Asserts.assertEquals(Double.NaN, dmul(2.0d, Double.NaN));

        // Rule: The sign of the result is positive if both values have the same sign,
        // and negative if the values have different signs.
        Asserts.assertEquals(6.0d, dmul(2.0d, 3.0d)); // Positive * Positive = Positive
        Asserts.assertEquals(-6.0d, dmul(-2.0d, 3.0d)); // Negative * Positive = Negative
        Asserts.assertEquals(-6.0d, dmul(2.0d, -3.0d)); // Positive * Negative = Negative
        Asserts.assertEquals(6.0d, dmul(-2.0d, -3.0d)); // Negative * Negative = Positive

        // Rule: Multiplication of an infinity by a zero results in NaN.
        Asserts.assertEquals(Double.NaN, dmul(Double.POSITIVE_INFINITY, 0.0d));
        Asserts.assertEquals(Double.NaN, dmul(Double.NEGATIVE_INFINITY, 0.0d));

        // Rule: Multiplication of an infinity by a finite value results in a signed infinity.
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dmul(Double.POSITIVE_INFINITY, 2.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dmul(Double.NEGATIVE_INFINITY, 2.0d));
        Asserts.assertEquals(Double.NEGATIVE_INFINITY, dmul(Double.POSITIVE_INFINITY, -2.0d));
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dmul(Double.NEGATIVE_INFINITY, -2.0d));

        // Rule: Normal multiplication with rounding and overflow/underflow handling.
        Asserts.assertEquals(6.0d, dmul(2.0d, 3.0d)); // Normal multiplication
        Asserts.assertEquals(Double.POSITIVE_INFINITY, dmul(Double.MAX_VALUE, 2.0d)); // Overflow
        Asserts.assertEquals(0.0d, dmul(Double.MIN_VALUE, Double.MIN_VALUE)); // Underflow
    }

    public static double dmul(double a, double b) {
        return a * b;
    }

}
