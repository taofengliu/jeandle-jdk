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
 * @compile Pop2.jasm TestPop2.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testIPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testFPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testAPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testSPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testCPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testBPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testLPop2*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop2::testDPop2*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestPop2
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestPop2 {
    public static void main(String[] args) {
        TestPop2.testIPop2(1, 5);
        TestPop2.testSPop2((short)2, (short)6);
        TestPop2.testCPop2((char)3, (char)7);
        TestPop2.testBPop2((byte)4, (byte)8);
        TestPop2.testFPop2(1.0f, 2.0f);
        TestPop2.testAPop2("Hello", "World", "Again");
        TestPop2.testLPop2(123l, 456l);
        TestPop2.testDPop2(1.1d, 2.2d);
    }

    public static void testIPop2(int a, int b) {
        int result = Pop2.testIPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testFPop2(float a, float b) {
        float result = Pop2.testFPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testAPop2(String a, String b, String c) {
        String result = Pop2.testAPop2(a, b, c);
        Asserts.assertEquals(result, a);
    }

    public static void testSPop2(short a, short b) {
        short result = (short)Pop2.testSPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testCPop2(char a, char b) {
        char result = (char)Pop2.testCPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testBPop2(byte a, byte b) {
        byte result = (byte)Pop2.testBPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testLPop2(long a, long b) {
        long result = Pop2.testLPop2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testDPop2(double a, double b) {
        double result = Pop2.testDPop2(a, b);
        Asserts.assertEquals(result, b);
    }
}
