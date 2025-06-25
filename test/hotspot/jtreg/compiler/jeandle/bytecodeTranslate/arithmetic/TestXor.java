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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestXor::testIxor
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestXor::testLxor
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestXor
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestXor {
    public static void main(String[] args) throws Exception {
        testIxor();
        testLxor();
    }

    private static void testIxor() {
        // Basic case
        int a = 0b1100;
        int b = 0b1010;
        int result = a ^ b;
        Asserts.assertEquals((int)0b0110, result);
        // Zero case
        a = 0;
        b = 0b1111;
        result = a ^ b;
        Asserts.assertEquals((int)0b1111, result);
        // NegativeNumber
        a = -1;
        b = 0b1111;
        result = a ^ b;
        Asserts.assertEquals(-16, result);
        // MaxMinValues
        a = Integer.MAX_VALUE;
        b = Integer.MIN_VALUE;
        result = a ^ b;
        Asserts.assertEquals(-1, result);
        // MaxMinValues/{2,3}
        a = Integer.MAX_VALUE / 2;
        b = Integer.MIN_VALUE / 3;
        result = a ^ b;
        Asserts.assertEquals(-357913943, result);
        // SameValue
        a = 12345;
        b = 12345;
        result = a ^ b;
        Asserts.assertEquals(0, result);
        // MixedSigns
        a = 12345;
        b = -98765;
        result = a ^ b;
        Asserts.assertEquals(-111094, result);
    }

    private static void testLxor() {
        // Basic case
        long a = 0b1100;
        long b = 0b1010;
        long result = a ^ b;
        Asserts.assertEquals((long)0b0110, result);
        // Zero case
        a = 0;
        b = 0b1111;
        result = a ^ b;
        Asserts.assertEquals((long)0b1111, result);
        // NegativeNumber
        a = -1;
        b = 0b1111;
        result = a ^ b;
        Asserts.assertEquals((long)-16, result);
        // MaxMinValues
        a = Long.MAX_VALUE;
        b = Long.MIN_VALUE;
        result = a ^ b;
        Asserts.assertEquals((long)-1, result);
        // MaxMinValues/{2,3}
        a = Long.MAX_VALUE / 2;
        b = Long.MIN_VALUE / 3;
        result = a ^ b;
        Asserts.assertEquals((long)-1537228672809129303l, (long)result);
        // SameValue
        a = 12345;
        b = 12345;
        result = a ^ b;
        Asserts.assertEquals((long)0, result);
        // MixedSigns
        a = 12345;
        b = -98765;
        result = a ^ b;
        Asserts.assertEquals((long)-111094, result);
    }
}
