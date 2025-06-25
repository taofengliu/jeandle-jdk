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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic::testSum
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic::testSub
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic::testMul
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic::testRem
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic::testDiv
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestArithmetic
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestArithmetic {

    public static void main(String[] args) throws Exception {
        testSum();
        testSub();
        testMul();
        testRem();
        testDiv();
    }

    static void testSum() {
        int a0 = 100;
        int b0 = 500;
        Asserts.assertEquals(a0 + b0, 600);

        long a1 = 203685477;
        long b1 = 233720368;
        Asserts.assertEquals(a1 + b1, 437405845l);
    }

    static void testSub() {
        int a0 = 100;
        int b0 = 500;
        Asserts.assertEquals(a0 - b0, -400);

        long a1 = 233720368;
        long b1 = 233720358;
        Asserts.assertEquals(a1 - b1, 10l);
    }

    static void testMul() {
        int a0 = 100;
        int b0 = 500;
        Asserts.assertEquals(a0 * b0, 50000);

        long a1 = 203685477;
        long b1 = 2;
        Asserts.assertEquals(a1 * b1, 407370954l);
    }

    static void testRem() {
        int a0 = 100;
        int b0 = 500;
        Asserts.assertEquals(b0 % a0, 0);

        long a1 = 203685477;
        long b1 = 2;
        Asserts.assertEquals(a1 % b1, 1l);
    }

    static void testDiv() {
        int a0 = 100;
        int b0 = 500;
        Asserts.assertEquals(b0 / a0, 5);

        long a1 = 233720368;
        long b1 = 233720368;
        Asserts.assertEquals(a1 / b1, 1l);
    }

}
