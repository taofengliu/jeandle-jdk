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
 * @compile Dup.jasm TestDup.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testIDup*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testFDup*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testADup*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testSDup*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testCDup*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.Dup::testBDup*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestDup
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestDup {
    public static void main(String[] args) {
        TestDup.testIDup(1, 5);
        TestDup.testSDup();
        TestDup.testCDup();
        TestDup.testBDup();
        TestDup.testFDup(1.0f, 2.0f);
        TestDup.testADup("Hello", "World");
    }

    public static void testIDup(int a, int b) {
        int result = Dup.testIDup1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup.testIDup2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testFDup(float a, float b) {
        float result = Dup.testFDup1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup.testFDup2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testADup(String a, String b) {
        String result = Dup.testADup1(a, b);
        Asserts.assertEquals(result, b);
        result = Dup.testADup2(a, b);
        Asserts.assertEquals(result, b);
    }

    public static void testSDup() {
        short result = (short)Dup.testSDup1();
        Asserts.assertEquals(result, (short)5);
        result = (short)Dup.testSDup2();
        Asserts.assertEquals(result, (short)5);
    }

    public static void testCDup() {
        char result = (char)Dup.testCDup1();
        Asserts.assertEquals(result, (char)4);
        result = (char)Dup.testCDup2();
        Asserts.assertEquals(result, (char)4);
    }

    public static void testBDup() {
        byte result = (byte)Dup.testBDup1();
        Asserts.assertEquals(result, (byte)1);
        result = (byte)Dup.testBDup2();
        Asserts.assertEquals(result, (byte)1);
    }
}
