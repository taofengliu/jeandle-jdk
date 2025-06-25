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
 * @compile Dup_x1.jasm TestDup_x1.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testIDup_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testFDup_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testADup_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testSDup_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testCDup_x1*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup_x1::testBDup_x1*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup_x1
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

// dup_x1
// Before: ..., value2, value1
// After:  ..., value1, value2, value1
public class TestDup_x1 {
    public static void main(String[] args) {
        TestDup_x1.testIDup_x1(1, 5);
        TestDup_x1.testSDup_x1();
        TestDup_x1.testCDup_x1();
        TestDup_x1.testBDup_x1();
        TestDup_x1.testFDup_x1(1.0f, 2.0f);
        TestDup_x1.testADup_x1("Hello", "World");
    }

    public static void testIDup_x1(int a, int b) {
        int result = Dup_x1.testIDup_x1_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup_x1.testIDup_x1_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup_x1.testIDup_x1_3(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testFDup_x1(float a, float b) {
        float result = Dup_x1.testFDup_x1_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup_x1.testFDup_x1_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup_x1.testFDup_x1_3(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testADup_x1(String a, String b) {
        String result = Dup_x1.testADup_x1_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup_x1.testADup_x1_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup_x1.testADup_x1_3(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testSDup_x1() {
        short result = (short)Dup_x1.testSDup_x1_1();
        Asserts.assertEquals(result, (short)6);
        result = (short)Dup_x1.testSDup_x1_2();
        Asserts.assertEquals(result, (short)5);
        result = (short)Dup_x1.testSDup_x1_3();
        Asserts.assertEquals(result, (short)6);
    }

    public static void testCDup_x1() {
        char result = (char)Dup_x1.testCDup_x1_1();
        Asserts.assertEquals(result, (char)4);
        result = (char)Dup_x1.testCDup_x1_2();
        Asserts.assertEquals(result, (char)3);
        result = (char)Dup_x1.testCDup_x1_3();
        Asserts.assertEquals(result, (char)4);
    }

    public static void testBDup_x1() {
        byte result = (byte)Dup_x1.testBDup_x1_1();
        Asserts.assertEquals(result, (byte)2);
        result = (byte)Dup_x1.testBDup_x1_2();
        Asserts.assertEquals(result, (byte)1);
        result = (byte)Dup_x1.testBDup_x1_3();
        Asserts.assertEquals(result, (byte)2);
    }
}
