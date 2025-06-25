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
 * @compile Dup_x2.jasm TestDup_x2.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x2*::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup_x2
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;


import jdk.test.lib.Asserts;

// dup_x2 Form1:
// Before: ..., value3, value2, value1
// After:  ..., value1, value3, value2, value1

// dup_x2 Form2(value2 is a value of a category 2 computational type):
// Before: ..., value2, value1
// After:  ..., value1, value2, value1

public class TestDup_x2 {
    public static void main(String[] args) {
        // Form1
        TestDup_x2.testIDup_x2(1, 5, 10);
        TestDup_x2.testSDup_x2();
        TestDup_x2.testCDup_x2();
        TestDup_x2.testBDup_x2();
        TestDup_x2.testFDup_x2(1.0f, 2.0f, 3.0f);
        TestDup_x2.testADup_x2("Hello", "World", "Welcome");

        // Form2
        TestDup_x2.testIDup_x2(0x123456789l, 7);
        TestDup_x2.testIDup_x2(3.0d, 7);
    }

    public static void testIDup_x2(int a, int b, int c) {
        int result = Dup_x2_Form1.testIDup_x2_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup_x2_Form1.testIDup_x2_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup_x2_Form1.testIDup_x2_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup_x2_Form1.testIDup_x2_4(a, b, c);
        Asserts.assertEquals(result, c);
    }

    public static void testFDup_x2(float a, float b, float c) {
        float result = Dup_x2_Form1.testFDup_x2_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup_x2_Form1.testFDup_x2_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup_x2_Form1.testFDup_x2_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup_x2_Form1.testFDup_x2_4(a, b, c);
        Asserts.assertEquals(result, c);
    }

    public static void testADup_x2(String a, String b, String c) {
        String result = Dup_x2_Form1.testADup_x2_1(a, b, c);
        Asserts.assertEquals(result, c);
        result = Dup_x2_Form1.testADup_x2_2(a, b, c);
        Asserts.assertEquals(result, b);
        result = Dup_x2_Form1.testADup_x2_3(a, b, c);
        Asserts.assertEquals(result, a);
        result = Dup_x2_Form1.testADup_x2_4(a, b, c);
        Asserts.assertEquals(result, c);
    }

    public static void testSDup_x2() {
        short result = (short)Dup_x2_Form1.testSDup_x2_1();
        Asserts.assertEquals(result, (short)7);
        result = (short)Dup_x2_Form1.testSDup_x2_2();
        Asserts.assertEquals(result, (short)6);
        result = (short)Dup_x2_Form1.testSDup_x2_3();
        Asserts.assertEquals(result, (short)5);
        result = (short)Dup_x2_Form1.testSDup_x2_4();
        Asserts.assertEquals(result, (short)7);
    }

    public static void testCDup_x2() {
        char result = (char)Dup_x2_Form1.testCDup_x2_1();
        Asserts.assertEquals(result, (char)5);
        result = (char)Dup_x2_Form1.testCDup_x2_2();
        Asserts.assertEquals(result, (char)4);
        result = (char)Dup_x2_Form1.testCDup_x2_3();
        Asserts.assertEquals(result, (char)3);
        result = (char)Dup_x2_Form1.testCDup_x2_4();
        Asserts.assertEquals(result, (char)5);
    }

    public static void testBDup_x2() {
        byte result = (byte)Dup_x2_Form1.testBDup_x2_1();
        Asserts.assertEquals(result, (byte)3);
        result = (byte)Dup_x2_Form1.testBDup_x2_2();
        Asserts.assertEquals(result, (byte)2);
        result = (byte)Dup_x2_Form1.testBDup_x2_3();
        Asserts.assertEquals(result, (byte)1);
        result = (byte)Dup_x2_Form1.testBDup_x2_4();
        Asserts.assertEquals(result, (byte)3);
    }

    public static void testIDup_x2(long a, int b) {
        int result1 = Dup_x2_Form2.testIDup_x2_1(a, b);
        Asserts.assertEquals(result1, b);
        long result2 = Dup_x2_Form2.testIDup_x2_2(a, b);
        Asserts.assertEquals(result2, a);
        int result3 = Dup_x2_Form2.testIDup_x2_3(a, b);
        Asserts.assertEquals(result3, b);
    }

    public static void testIDup_x2(double a, int b) {
        int result1 = Dup_x2_Form2.testIDup_x2_1(a, b);
        Asserts.assertEquals(result1, b);
        double result2 = Dup_x2_Form2.testIDup_x2_2(a, b);
        Asserts.assertEquals(result2, a);
        int result3 = Dup_x2_Form2.testIDup_x2_3(a, b);
        Asserts.assertEquals(result3, b);
    }
}
