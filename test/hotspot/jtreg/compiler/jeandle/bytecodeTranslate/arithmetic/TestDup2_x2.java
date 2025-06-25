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
 * @compile Dup2_x2.jasm TestDup2_x2.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x2::testIDup2_x2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x2::testFDup2_x2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x2::testADup2_x2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2_x2::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup2_x2
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

// Form1: ..., value4, value3, value2, value1 -> ..., value2, value1, value4, value3, value2, value1
// Form4: ..., value2, value1 -> ..., value1, value2, value1
// Form2: ..., value3, value2, value1(DJ) -> ..., value1, value3, value2, value1(DJ)
// Form3: ..., value3(DJ), value2, value1 -> ..., value2, value1, value3(DJ), value2, value1
public class TestDup2_x2 {
    public static void main(String[] args) {
        // Form1
        TestDup2_x2.testIDup2_x2(1, 5, 7, 13);
        TestDup2_x2.testFDup2_x2(1.0f, 5.0f, 7.0f, 13.0f);
        TestDup2_x2.testADup2_x2("Hello", "World", "Welcome", "CHINA");
        // Form4
        TestDup2_x2.testJDup2_x2(0x123456789l, 0x987654321l);
        TestDup2_x2.testDDup2_x2(3.14d, 9.26d);
        //Form2
        TestDup2_x2.testIIJDup2_x2(1, 5, 0x123456789l);
        //Form3
        TestDup2_x2.testJIIDup2_x2(0x123456789l, 1, 5);
    }

    public static void testIDup2_x2(int a, int b, int c, int d) {
        int result = Dup2_x2.testIDup2_x2_1(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testIDup2_x2_2(a, b, c, d);
        Asserts.assertEquals(result, c);
        result = Dup2_x2.testIDup2_x2_3(a, b, c, d);
        Asserts.assertEquals(result, b);
        result = Dup2_x2.testIDup2_x2_4(a, b, c, d);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testIDup2_x2_5(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testIDup2_x2_6(a, b, c, d);
        Asserts.assertEquals(result, c);
    }

    public static void testFDup2_x2(float a, float b, float c, float d) {
        float result = Dup2_x2.testFDup2_x2_1(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testFDup2_x2_2(a, b, c, d);
        Asserts.assertEquals(result, c);
        result = Dup2_x2.testFDup2_x2_3(a, b, c, d);
        Asserts.assertEquals(result, b);
        result = Dup2_x2.testFDup2_x2_4(a, b, c, d);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testFDup2_x2_5(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testFDup2_x2_6(a, b, c, d);
        Asserts.assertEquals(result, c);
    }

    public static void testADup2_x2(String a, String b, String c, String d) {
        String result = Dup2_x2.testADup2_x2_1(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testADup2_x2_2(a, b, c, d);
        Asserts.assertEquals(result, c);
        result = Dup2_x2.testADup2_x2_3(a, b, c, d);
        Asserts.assertEquals(result, b);
        result = Dup2_x2.testADup2_x2_4(a, b, c, d);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testADup2_x2_5(a, b, c, d);
        Asserts.assertEquals(result, d);
        result = Dup2_x2.testADup2_x2_6(a, b, c, d);
        Asserts.assertEquals(result, c);
    }

    public static void testJDup2_x2(long a, long b) {
        long result = Dup2_x2.testJDup2_x2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2_x2.testJDup2_x2_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testJDup2_x2_3(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testDDup2_x2(double a, double b) {
        double result = Dup2_x2.testDDup2_x2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2_x2.testDDup2_x2_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testDDup2_x2_3(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testIIJDup2_x2(int a, int b, long c) {
        long result1 = Dup2_x2.testIIJDup2_x2_1(a, b, c);
        Asserts.assertEquals(result1, c);
        int result2 = Dup2_x2.testIIJDup2_x2_2(a, b, c);
        Asserts.assertEquals(result2, b);
        int result3 = Dup2_x2.testIIJDup2_x2_3(a, b, c);
        Asserts.assertEquals(result3, a);
        long result4 = Dup2_x2.testIIJDup2_x2_4(a, b, c);
        Asserts.assertEquals(result4, c);
    }

    public static void testJIIDup2_x2(long a, int b, int c) {
        long result = Dup2_x2.testJIIDup2_x2_1(a, b, c);
        Asserts.assertEquals(result, (long)c);
        result = Dup2_x2.testJIIDup2_x2_2(a, b, c);
        Asserts.assertEquals(result, (long)b);
        result = Dup2_x2.testJIIDup2_x2_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup2_x2.testJIIDup2_x2_4(a, b, c);
        Asserts.assertEquals(result, (long)c);
        result = Dup2_x2.testJIIDup2_x2_5(a, b, c);
        Asserts.assertEquals(result, (long)b);
    }
}
