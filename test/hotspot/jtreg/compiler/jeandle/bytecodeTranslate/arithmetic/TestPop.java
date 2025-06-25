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
 * @compile Pop.jasm TestPop.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testIPop*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testFPop*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testAPop*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testSPop*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testCPop*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Pop::testBPop*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestPop
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestPop {
    public static void main(String[] args) {
        TestPop.testIPop(1, 5);
        TestPop.testSPop((short)2, (short)6);
        TestPop.testCPop((char)3, (char)7);
        TestPop.testBPop((byte)4, (byte)8);
        TestPop.testFPop(1.0f, 2.0f);
        TestPop.testAPop("Hello", "World");
    }

    public static void testIPop(int a, int b) {
        int result = Pop.testIPop(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testFPop(float a, float b) {
        float result = Pop.testFPop(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testAPop(String a, String b) {
        String result = Pop.testAPop(a, b);
        Asserts.assertEquals(result, a);
    }

    public static void testSPop(short a, short b) {
        short result = (short)Pop.testSPop(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testCPop(char a, char b) {
        char result = (char)Pop.testCPop(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testBPop(byte a, byte b) {
        byte result = (byte)Pop.testBPop(a, b);
        Asserts.assertEquals(result, b);
    }
}
