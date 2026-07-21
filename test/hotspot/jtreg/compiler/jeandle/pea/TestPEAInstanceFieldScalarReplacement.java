/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @summary PEA instance-field scalar replacement across all Java field kinds
 *          (boolean/byte/short/char/int/long/float/double/Object): write-read,
 *          overwrite, interleaved int-like fields, and default-value read.
 *          NeverEscape in every case, so the allocation and every field
 *          load/store must vanish. Correctness uses parameter-driven values
 *          (float/double raw bits compared in the interpreted driver, not via
 *          intrinsics inside the compiled method).
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAInstanceFieldScalarReplacement
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestPEAInstanceFieldScalarReplacement {
    static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAInstanceFieldScalarReplacement$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method[] targets = {
                TestWrapper.class.getMethod("testIntFields",
                        boolean.class, byte.class, short.class, char.class, int.class),
                TestWrapper.class.getMethod("testLongField", long.class),
                TestWrapper.class.getMethod("testFloatField", float.class),
                TestWrapper.class.getMethod("testDoubleField", double.class),
                TestWrapper.class.getMethod("testReferenceField", Object.class),
                TestWrapper.class.getMethod("testDefaultInt"),
                TestWrapper.class.getMethod("testDefaultLong"),
                TestWrapper.class.getMethod("testDefaultFloat"),
                TestWrapper.class.getMethod("testDefaultDouble"),
                TestWrapper.class.getMethod("testDefaultReference"),
                TestWrapper.class.getMethod("testOverwriteInt", int.class, int.class),
                TestWrapper.class.getMethod("testOverwriteDouble", double.class, double.class),
        };

        PEATestUtils.Run run = PEATestUtils.run(WRAPPER)
                .llvmOptions(PEATestUtils.peaLLVMOptionsClass(TestWrapper.class));
        for (Method m : targets) {
            run.compileonly(m.getName());
        }
        Path dumpDir = run.dumpDir();
        OutputAnalyzer out = run.run();

        PEATestUtils.assertEffect(out, "EliminateAllocation");
        for (Method m : targets) {
            PEATestUtils.assertStats(out, m, 1, 0, 0);
            // NeverEscape via the PEA target: @jeandle.new_instance present
            // before PEA, eliminated after.
            PEATestUtils.assertNeverEscapes(out, m);
            // Field accesses also vanish in the final IR.
            PEATestUtils.PEABody body = new PEATestUtils.PEABody(dumpDir, m, true);
            body.assertAbsent("store atomic");
            body.assertAbsent("load atomic");
        }
        for (String label : new String[] {"intFields", "longField", "floatField", "doubleField",
                "referenceField", "defaultInt", "defaultLong", "defaultFloat", "defaultDouble",
                "defaultReference", "overwriteInt", "overwriteDouble"}) {
            out.shouldContain(label + ": ok");
        }
    }

    public static class TestWrapper {
        // POJO spanning every Java field kind and size/alignment.
        public static class Fields {
            public boolean z;
            public byte b;
            public short s;
            public char c;
            public int i;
            public long j;
            public float f;
            public double d;
            public Object o;
        }

        public static void main(String[] args) {
            new Fields(); // real class-init so test() compiles a real body

            // int-like kinds, interleaved writes/reads, distinct multipliers.
            chkLong(testIntFields(true, (byte) -128, (short) -32768, (char) 0xFFFF, Integer.MIN_VALUE),
                    expIntFields(true, (byte) -128, (short) -32768, (char) 0xFFFF, Integer.MIN_VALUE));
            chkLong(testIntFields(false, (byte) 127, (short) 32767, (char) 0, Integer.MAX_VALUE),
                    expIntFields(false, (byte) 127, (short) 32767, (char) 0, Integer.MAX_VALUE));
            chkLong(testIntFields(true, (byte) 0, (short) -1, (char) 'A', 42),
                    expIntFields(true, (byte) 0, (short) -1, (char) 'A', 42));
            System.out.println("intFields: ok");

            chkLong(testLongField(0x123456789ABCDEF0L), 0x123456789ABCDEF0L);
            chkLong(testLongField(Long.MIN_VALUE), Long.MIN_VALUE);
            chkLong(testLongField(Long.MAX_VALUE), Long.MAX_VALUE);
            System.out.println("longField: ok");

            chkFloat(testFloatField(Float.NaN), Float.NaN);
            chkFloat(testFloatField(-0.0f), -0.0f);
            chkFloat(testFloatField(+0.0f), +0.0f);
            chkFloat(testFloatField(Float.MAX_VALUE), Float.MAX_VALUE);
            chkFloat(testFloatField(Float.MIN_VALUE), Float.MIN_VALUE);
            chkFloat(testFloatField(Float.NEGATIVE_INFINITY), Float.NEGATIVE_INFINITY);
            System.out.println("floatField: ok");

            chkDouble(testDoubleField(Double.NaN), Double.NaN);
            chkDouble(testDoubleField(-0.0), -0.0);
            chkDouble(testDoubleField(Double.MAX_VALUE), Double.MAX_VALUE);
            chkDouble(testDoubleField(Double.MIN_VALUE), Double.MIN_VALUE);
            System.out.println("doubleField: ok");

            Object a = new Object();
            Object b = new Object();
            Asserts.assertTrue(testReferenceField(a) == a);
            Asserts.assertTrue(testReferenceField(b) == b);
            Asserts.assertTrue(testReferenceField(null) == null);
            System.out.println("referenceField: ok");

            Asserts.assertEquals(testDefaultInt(), 0);
            System.out.println("defaultInt: ok");
            Asserts.assertEquals(testDefaultLong(), 0L);
            System.out.println("defaultLong: ok");
            // Default float/double are +0.0 (raw bits 0).
            Asserts.assertEquals(Float.floatToRawIntBits(testDefaultFloat()), 0);
            System.out.println("defaultFloat: ok");
            Asserts.assertEquals(Double.doubleToRawLongBits(testDefaultDouble()), 0L);
            System.out.println("defaultDouble: ok");
            Asserts.assertTrue(testDefaultReference() == null);
            System.out.println("defaultReference: ok");

            Asserts.assertEquals(testOverwriteInt(11, 22), 22);
            Asserts.assertEquals(testOverwriteInt(-1, 0), 0);
            System.out.println("overwriteInt: ok");
            chkDouble(testOverwriteDouble(3.5, -7.25), -7.25);
            chkDouble(testOverwriteDouble(Double.NaN, 1.0), 1.0);
            System.out.println("overwriteDouble: ok");
        }

        // ---- compiled targets (frontend-supported ops only) ----

        public static long testIntFields(boolean z, byte by, short sh, char ch, int in) {
            Fields f = new Fields();
            f.z = z; f.b = by; f.s = sh; f.c = ch; f.i = in;
            return (long) f.i * 7L + (long) f.b * 5L + (long) f.s * 3L
                    + (long) (int) f.c * 11L + (long) (f.z ? 101 : 0);
        }

        public static long testLongField(long j) { Fields f = new Fields(); f.j = j; return f.j; }
        public static float testFloatField(float fv) { Fields f = new Fields(); f.f = fv; return f.f; }
        public static double testDoubleField(double dv) { Fields f = new Fields(); f.d = dv; return f.d; }
        public static Object testReferenceField(Object o) { Fields f = new Fields(); f.o = o; return f.o; }

        public static int testDefaultInt() { Fields f = new Fields(); return f.i; }
        public static long testDefaultLong() { Fields f = new Fields(); return f.j; }
        public static float testDefaultFloat() { Fields f = new Fields(); return f.f; }
        public static double testDefaultDouble() { Fields f = new Fields(); return f.d; }
        public static Object testDefaultReference() { Fields f = new Fields(); return f.o; }

        public static int testOverwriteInt(int a, int b) { Fields f = new Fields(); f.i = a; f.i = b; return f.i; }
        public static double testOverwriteDouble(double x, double y) { Fields f = new Fields(); f.d = x; f.d = y; return f.d; }

        // ---- interpreted reference oracles ----

        static long expIntFields(boolean z, byte by, short sh, char ch, int in) {
            return (long) in * 7L + (long) by * 5L + (long) sh * 3L
                    + (long) (int) ch * 11L + (long) (z ? 101 : 0);
        }

        static void chkLong(long got, long exp) { Asserts.assertEquals(got, exp); }
        static void chkFloat(float got, float exp) {
            Asserts.assertEquals(Float.floatToRawIntBits(got), Float.floatToRawIntBits(exp));
        }
        static void chkDouble(double got, double exp) {
            Asserts.assertEquals(Double.doubleToRawLongBits(got), Double.doubleToRawLongBits(exp));
        }
    }
}
