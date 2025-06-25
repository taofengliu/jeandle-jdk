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
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testIshl*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testIshr*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testIushr*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testLshl*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testLshr*
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.arithmetic.TestShift::testLushr*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.arithmetic.TestShift
 */

package compiler.jeandle.bytecodeTranslate.arithmetic;

import jdk.test.lib.Asserts;

public class TestShift {
    public static void main(String[] args) throws Exception {
        testIshl();
        testIshr();
        testIushr();
        testLshl();
        testLshr();
        testLushr();
    }

    private static void testIshl() {
        int a = -1;
        int b = -1;
        int result = -1;

        //Basic Case
        a = 0b0001;
        b = 2;
        result = a << b;
        Asserts.assertEquals((int)0b0100, result);

        //Zero Shift
        a = 0b0001;
        b = 0;
        result = a << b;
        Asserts.assertEquals((int)0b0001, result);

        // Negative Number
        a = -1;
        b = 1;
        result = a << b;
        Asserts.assertEquals((int)-2, result);

        // Overflow
        a = Integer.MAX_VALUE;
        b = 1;
        result = a << b;
        Asserts.assertEquals((int)-2, result);

        // Large Shift
        a = 0b0001;
        b = 33;
        result = a << b;
        // System.out.println(result);
        Asserts.assertEquals(0b0010, result);

        // Mixed Signs
        a = 12345;
        b = 3;
        result = a << b;
        Asserts.assertEquals((int)98760, result);
        a = 12345;
        b = -3;
        result = a << b;
        Asserts.assertEquals((int)536870912, result);
        a = -12345;
        b = 3;
        result = a << b;
        Asserts.assertEquals((int)-98760, result);
        a = -12345;
        b = -3;
        result = a << b;
        Asserts.assertEquals((int)-536870912, result);

        // Zero input
        a = 0;
        b = 0;
        result = a << b;
        Asserts.assertEquals((int)0, result);
        a = 0x1234;
        b = 0;
        result = a << b;
        Asserts.assertEquals((int)0x1234, result);
        a = 0;
        b = 15;
        result = a << b;
        Asserts.assertEquals((int)0, result);

        // Minus shift
        a = 1;
        b = -1;
        result = a << b;
        Asserts.assertEquals((int)-2147483648, result);
        a = 1;
        b = -33;
        result = a << b;
        Asserts.assertEquals((int)-2147483648, result);
    }

    private static void testIshr() {
        int a = -1;
        int b = -1;
        int result = -1;

        //Basic Case
        a = 0b0100;
        b = 2;
        result = a >> b;
        Asserts.assertEquals((int)0b001, result);
        a = 0b0101;
        b = 2;
        result = a >> b;
        Asserts.assertEquals((int)0b001, result);
        a = Integer.MAX_VALUE;
        b = 5;
        result = a >> b;
        Asserts.assertEquals((int)67108863, result);

        //Zero Shift
        a = 0b0001;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((int)0b0001, result);

        // Negative Number
        a = -1;
        b = 1;
        result = a >> b;
        Asserts.assertEquals((int)-1, result);

        // Underflow
        a = 0;
        b = 5;
        result = a >> b;
        Asserts.assertEquals((int)0, result);

        // Large Shift
        a = 0b0001;
        b = 33;
        result = a >> b;
        Asserts.assertEquals((int)0, result);
        a = 5;
        b = 32;
        result = a >> b;
        Asserts.assertEquals((int)5, result);
        a = 4;
        b = 33;
        result = a >> b;
        Asserts.assertEquals((int)2, result);

        // Mixed Signs
        a = 12345;
        b = 3;
        result = a >> b;
        Asserts.assertEquals((int)1543, result);
        a = 12345;
        b = -3;
        result = a >> b;
        Asserts.assertEquals((int)0, result);
        a = -12345;
        b = 3;
        result = a >> b;
        Asserts.assertEquals((int)-1544, result);
        a = -12345;
        b = -3;
        result = a >> b;
        Asserts.assertEquals((int)-1, result);

        // Zero input
        a = 0;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((int)0, result);
        a = 0x1234;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((int)0x1234, result);
        a = 0;
        b = 15;
        result = a >> b;
        Asserts.assertEquals((int)0, result);

        // Minus shift
        a = 1;
        b = -1;
        result = a >> b;
        Asserts.assertEquals((int)0, result);
        a = 0x1000;
        b = -33;
        result = a >> b;
        Asserts.assertEquals((int)0, result);
    }

    private static void testIushr() {
        // Case 1: Normal right shift with positive value
        int value1 = 0b1100_0000_0000_0000_0000_0000_0000_0000; // 0xC0000000
        int shift1 = 4;
        int result1 = value1 >>> shift1;
        Asserts.assertEquals(result1, (int)0b0000_1100_0000_0000_0000_0000_0000_0000);

        // Case 2: Normal right shift with negative value
        int value2 = 0b1111_1111_1111_1111_1111_1111_1111_1111; // -1
        int shift2 = 4;
        int result2 = value2 >>> shift2;
        Asserts.assertEquals(result2, (int)0b0000_1111_1111_1111_1111_1111_1111_1111);

        // Case 3: Shift by 0 bits
        int value3 = 0b1100_0000_0000_0000_0000_0000_0000_0000; // 0xC0000000
        int shift3 = 0;
        int result3 = value3 >>> shift3;
        Asserts.assertEquals(result3, value3);

        // Case 4: Shift by more than 31 bits (mod 32)
        int value4 = 0b1100_0000_0000_0000_0000_0000_0000_0000; // 0xC0000000
        int shift4 = 36; // Equivalent to 36 % 32 = 4
        int result4 = value4 >>> shift4;
        Asserts.assertEquals(result4, (int)0b0000_1100_0000_0000_0000_0000_0000_0000);

        // Case 5: Shift by exactly 32 bits (mod 32 = 0)
        int value5 = 0b1100_0000_0000_0000_0000_0000_0000_0000; // 0xC0000000
        int shift5 = 32; // Equivalent to 32 % 32 = 0
        int result5 = value5 >>> shift5;
        Asserts.assertEquals(result5, value5);
    }

    private static void testLshl() {
        long a = -1;
        int b = -1;
        long result = -1;

        //Basic Case
        a = 0b0001;
        b = 2;
        result = a << b;
        Asserts.assertEquals((long)0b0100, result);

        //Zero Shift
        a = 0b0001;
        b = 0;
        result = a << b;
        Asserts.assertEquals((long)0b0001, result);

        // Negative Number
        a = -1;
        b = 1;
        result = a << b;
        Asserts.assertEquals((long)-2, result);

        // Overflow
        a = Long.MAX_VALUE;
        b = 1;
        result = a << b;
        Asserts.assertEquals((long)-2, result);

        // Large Shift
        a = 0b0001;
        b = 65;
        result = a << b;
        // System.out.println(result);
        Asserts.assertEquals((long)0b0010, result);

        // Mixed Signs
        a = 12345;
        b = 3;
        result = a << b;
        Asserts.assertEquals((long)98760, result);
        a = 12345;
        b = -3;
        result = a << b;
        Asserts.assertEquals((long)2305843009213693952l, result);
        a = -12345;
        b = 3;
        result = a << b;
        Asserts.assertEquals((long)-98760, result);
        a = -12345;
        b = -3;
        result = a << b;
        Asserts.assertEquals((long)-2305843009213693952l, result);

        // Zero input
        a = 0;
        b = 0;
        result = a << b;
        Asserts.assertEquals((long)0, result);
        a = 0x1234;
        b = 0;
        result = a << b;
        Asserts.assertEquals((long)0x1234, result);
        a = 0;
        b = 15;
        result = a << b;
        Asserts.assertEquals((long)0, result);

        // Minus shift
        a = 1;
        b = -1;
        result = a << b;
        Asserts.assertEquals((long)-9223372036854775808l, result);
        a = 1;
        b = -65;
        result = a << b;
        Asserts.assertEquals((long)Long.MIN_VALUE, result);
    }

    private static void testLshr() {
        long a = -1;
        int b = -1;
        long result = -1;

        //Basic Case
        a = 0b0100;
        b = 2;
        result = a >> b;
        Asserts.assertEquals((long)0b001, result);
        a = 0b0101;
        b = 2;
        result = a >> b;
        Asserts.assertEquals((long)0b001, result);
        a = Long.MAX_VALUE;
        b = 5;
        result = a >> b;
        Asserts.assertEquals((long)288230376151711743l, result);

        //Zero Shift
        a = 0b0001;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((long)0b0001, result);

        // Negative Number
        a = -1;
        b = 1;
        result = a >> b;
        Asserts.assertEquals((long)-1, result);

        // Underflow
        a = 0;
        b = 5;
        result = a >> b;
        Asserts.assertEquals((long)0, result);

        // Large Shift
        a = 0b0001;
        b = 65;
        result = a >> b;
        Asserts.assertEquals((long)0, result);
        a = 5;
        b = 64;
        result = a >> b;
        Asserts.assertEquals((long)5, result);
        a = 4;
        b = 65;
        result = a >> b;
        Asserts.assertEquals((long)2, result);

        // Mixed Signs
        a = 12345;
        b = 3;
        result = a >> b;
        Asserts.assertEquals((long)1543, result);
        a = 12345;
        b = -3;
        result = a >> b;
        Asserts.assertEquals((long)0, result);
        a = -12345;
        b = 3;
        result = a >> b;
        Asserts.assertEquals((long)-1544, result);
        a = -12345;
        b = -3;
        result = a >> b;
        Asserts.assertEquals((long)-1, result);

        // Zero input
        a = 0;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((long)0, result);
        a = 0x1234;
        b = 0;
        result = a >> b;
        Asserts.assertEquals((long)0x1234, result);
        a = 0;
        b = 15;
        result = a >> b;
        Asserts.assertEquals((long)0, result);

        // Minus shift
        a = 1;
        b = -1;
        result = a >> b;
        Asserts.assertEquals((long)0, result);
        a = 0x1000;
        b = -65;
        result = a >> b;
        Asserts.assertEquals((long)0, result);
    }

    private static void testLushr() {
        // Case 1: Normal right shift with positive value
        long value1 = 0xC000000000l;
        int shift1 = 4;
        long result1 = value1 >>> shift1;
        Asserts.assertEquals(result1, (long)51539607552l);

        // Case 2: Normal right shift with negative value
        long value2 = -1;
        int shift2 = 4;
        long result2 = value2 >>> shift2;
        Asserts.assertEquals(result2, (long)1152921504606846975l);

        // Case 3: Shift by 0 bits
        long value3 = 0xC000000000l;
        int shift3 = 0;
        long result3 = value3 >>> shift3;
        Asserts.assertEquals(result3, value3);

        // Case 4: Shift by more than 63 bits (mod 64)
        long value4 = 0xC000000000l;
        int shift4 = 68; // Equivalent to 68 % 64 = 4
        long result4 = value4 >>> shift4;
        Asserts.assertEquals(result4, (long)0xC00000000l);

        // Case 5: Shift by exactly 64 bits (mod 64 = 0)
        long value5 = 0xC000000000l;
        int shift5 = 64; // Equivalent to 64 % 64 = 0
        long result5 = value5 >>> shift5;
        Asserts.assertEquals(result5, value5);
    }
}
