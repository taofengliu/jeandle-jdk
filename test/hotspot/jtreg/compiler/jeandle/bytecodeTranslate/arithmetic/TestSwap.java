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
 * @compile Swap.jasm TestSwap.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testIswap*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testFswap*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testAswap*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testSswap*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testCswap*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Swap::testBswap*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestSwap
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestSwap {
    public static void main(String[] args) {
        for (int i = 0; i < 1<<15; i++) {
            TestSwap.testIswap(i, 1);
        }
        for (int i = 0; i < 1<<15; i++) {
            TestSwap.testIswap(1, i);
        }
        for (short i = 0; i < 1<<7; i++) {
            TestSwap.testSswap((short)1, i);
        }
        for (char i = 0; i < 1<<7; i++) {
            TestSwap.testCswap((char)1, i);
        }
        for (byte i = 0; i < 1<<3; i++) {
            TestSwap.testBswap((byte)1, i);
        }
        TestSwap.testFswap(1.0f, 2.0f);
        TestSwap.testAswap("Hello", "World");
    }

    public static void testIswap(int a, int b) {
        int result = Swap.testIswap(a, b);
        Asserts.assertEquals(result, b+(a<<16));
    }

    public static void testFswap(float a, float b) {
        float result = Swap.testFswap(a, b);
        Asserts.assertEquals(result, a*2.0f+b);
    }

    public static void testAswap(String a, String b) {
        String result = Swap.testAswap(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testSswap(short a, short b) {
        short result = (short)Swap.testSswap(a, b);
        Asserts.assertEquals(result, (short)(b+(a<<8)));
    }

    public static void testCswap(char a, char b) {
        char result = (char)Swap.testCswap(a, b);
        Asserts.assertEquals(result, (char)(b+(a<<8)));
    }

    public static void testBswap(byte a, byte b) {
        byte result = (byte)Swap.testBswap(a, b);
        Asserts.assertEquals(result, (byte)(b+(a<<4)));
    }
}
