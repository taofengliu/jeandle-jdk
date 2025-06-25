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
 * @compile Dup2_x1.jasm TestDup2_x1.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::testIDup2_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::testFDup2_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::testADup2_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::testIJDup2_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::testIDDup2_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x1::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup2_x1
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

// Form1: ..., value3, value2, value1 -> ..., value2, value1, value3, value2, value1
// Form2: ..., value2, value1 -> ..., value1, value2, value1
public class TestDup2_x1 {
    public static void main(String[] args) {
        // Form1
        TestDup2_x1.testIDup2_x1(1, 5, 7);
        TestDup2_x1.testFDup2_x1(1.0f, 2.0f, 5.0f);
        TestDup2_x1.testADup2_x1("Hello", "World", "Welcome");
        // // Form2
        TestDup2_x1.testIJDup2_x1(1, 0x123456789l);
        TestDup2_x1.testIDDup2_x1(1, 3.0d);
    }

    public static void testIDup2_x1(int a, int b, int c) {
        int result = Dup2_x1.testIDup2_x1_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testIDup2_x1_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup2_x1.testIDup2_x1_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup2_x1.testIDup2_x1_4(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testIDup2_x1_5(a, b, c);
        Asserts.assertEquals(result, b);
    }

    public static void testFDup2_x1(float a, float b, float c) {
        float result = Dup2_x1.testFDup2_x1_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testFDup2_x1_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup2_x1.testFDup2_x1_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup2_x1.testFDup2_x1_4(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testFDup2_x1_5(a, b, c);
        Asserts.assertEquals(result, b);
    }

    public static void testADup2_x1(String a, String b, String c) {
        String result = Dup2_x1.testADup2_x1_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testADup2_x1_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup2_x1.testADup2_x1_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup2_x1.testADup2_x1_4(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup2_x1.testADup2_x1_5(a, b, c);
        Asserts.assertEquals(result, b);
    }

    public static void testIJDup2_x1(int a, long b) {
        long result1 = Dup2_x1.testIJDup2_x1_1(a, b);
        Asserts.assertEquals(result1, b);
        int result2 = Dup2_x1.testIJDup2_x1_2(a, b);
        Asserts.assertEquals(result2, a);
        long result3 = Dup2_x1.testIJDup2_x1_3(a, b);
        Asserts.assertEquals(result3, b);
    }

    public static void testIDDup2_x1(int a, double b) {
        double result1 = Dup2_x1.testIDDup2_x1_1(a, b);
        Asserts.assertEquals(result1, b);
        int result2 = Dup2_x1.testIDDup2_x1_2(a, b);
        Asserts.assertEquals(result2, a);
        double result3 = Dup2_x1.testIDDup2_x1_3(a, b);
        Asserts.assertEquals(result3, b);
    }
}
