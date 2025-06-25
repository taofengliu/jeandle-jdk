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
 * @compile Dup2.jasm TestDup2.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2::testIDup2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2::testFDup2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2::testADup2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2::testJDup2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup2::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup2
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

// Form1: ..., value2, value1 -> ..., value2, value1, value2, value1
// Form2: ..., value -> ..., value, value
public class TestDup2 {
    public static void main(String[] args) {
        // Form1
        TestDup2.testIDup2(1, 5);
        TestDup2.testFDup2(1.0f, 2.0f);
        TestDup2.testADup2("Hello", "World");
        // Form2
        TestDup2.testJDup2(0x123456789l, 0x987654321l);
    }

    public static void testIDup2(int a, int b) {
        int result = Dup2.testIDup2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testIDup2_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup2.testIDup2_3(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testIDup2_4(a, b);
        Asserts.assertEquals(result, a);
    }

    public static void testFDup2(float a, float b) {
        float result = Dup2.testFDup2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testFDup2_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup2.testFDup2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testFDup2_2(a, b);
        Asserts.assertEquals(result, a);
    }

    public static void testADup2(String a, String b) {
        String result = Dup2.testADup2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testADup2_2(a, b);
        Asserts.assertEquals(result, a);
        result = Dup2.testADup2_3(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testADup2_4(a, b);
        Asserts.assertEquals(result, a);
    }

    public static void testJDup2(long a, long b) {
        long result = Dup2.testJDup2_1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testJDup2_2(a, b);
        Asserts.assertEquals(result, b);
        result = Dup2.testJDup2_3(a, b);
        Asserts.assertEquals(result, a);
    }
}
