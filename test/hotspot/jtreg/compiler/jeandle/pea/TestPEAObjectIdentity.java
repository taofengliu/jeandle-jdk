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
 * @summary PEA object-identity folding (foldICmpEquality): == / != between
 *          virtual objects must fold to the right constant — self-equality
 *          (true), distinct allocations (false), virtual-vs-null (false),
 *          virtual-vs-external (false), aliased references (true), and identity
 *          unaffected by field writes. Identity is never conflated with
 *          address; the icmp is RAUW'd to a constant (ReplaceLoad) and the
 *          allocation vanishes. Plus a virtual-vs-materialized comparison.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAObjectIdentity
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestPEAObjectIdentity {
    static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAObjectIdentity$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method mSelf = TestWrapper.class.getMethod("testSelfEqual");
        Method mDistinct = TestWrapper.class.getMethod("testTwoDistinctNotEqual");
        Method mNull = TestWrapper.class.getMethod("testVsNull");
        Method mExt = TestWrapper.class.getMethod("testVsExternal", Object.class);
        Method mAlias = TestWrapper.class.getMethod("testTwoAliases");
        Method mField = TestWrapper.class.getMethod("testFieldChangeAroundCompare");
        Method mVM = TestWrapper.class.getMethod("testVirtualVsMaterialized");
        Method[] all = {mSelf, mDistinct, mNull, mExt, mAlias, mField, mVM};

        PEATestUtils.Run run = PEATestUtils.run(WRAPPER)
                .llvmOptions(PEATestUtils.peaLLVMOptionsClass(TestWrapper.class));
        for (Method m : all) {
            run.compileonly(m.getName());
        }
        OutputAnalyzer out = run.run();

        // Every foldable identity icmp produces a ReplaceLoad effect (RAUW to a
        // constant). testTwoDistinctNotEqual has two VOs; the rest one.
        PEATestUtils.assertEffect(out, "ReplaceLoad");
        PEATestUtils.assertStats(out, mSelf, 1, 0, 0);
        PEATestUtils.assertStats(out, mDistinct, 2, 0, 0);
        PEATestUtils.assertStats(out, mNull, 1, 0, 0);
        PEATestUtils.assertStats(out, mExt, 1, 0, 0);
        PEATestUtils.assertStats(out, mAlias, 1, 0, 0);
        PEATestUtils.assertStats(out, mField, 1, 0, 0);
        for (Method m : new Method[] {mSelf, mDistinct, mNull, mExt, mAlias, mField}) {
            PEATestUtils.assertNeverEscapes(out, m);
        }
        // testVirtualVsMaterialized: one object escapes (OrigAlloc retained),
        // the other is eliminated; the comparison still resolves to false.
        PEATestUtils.assertAllocRetained(out, mVM, 1);

        out.shouldContain("self: true");
        out.shouldContain("distinct: false");
        out.shouldContain("null: false");
        out.shouldContain("external: false");
        out.shouldContain("alias: true");
        out.shouldContain("field: true");
        out.shouldContain("vm: false");
    }

    public static class TestWrapper {
        public static class P { public int x; }
        static P sink; // forces an object to materialize

        public static void main(String[] args) {
            new P(); // resolve P
            Asserts.assertTrue(testSelfEqual());
            System.out.println("self: true");
            Asserts.assertFalse(testTwoDistinctNotEqual());
            System.out.println("distinct: false");
            Asserts.assertFalse(testVsNull());
            System.out.println("null: false");
            Asserts.assertFalse(testVsExternal(new Object()));
            System.out.println("external: false");
            Asserts.assertTrue(testTwoAliases());
            System.out.println("alias: true");
            Asserts.assertTrue(testFieldChangeAroundCompare());
            System.out.println("field: true");
            Asserts.assertFalse(testVirtualVsMaterialized());
            System.out.println("vm: false");
        }

        public static boolean testSelfEqual() { P p = new P(); return p == p; }
        public static boolean testTwoDistinctNotEqual() { P a = new P(); P b = new P(); return a == b; }
        public static boolean testVsNull() { P p = new P(); return p == null; }
        public static boolean testVsExternal(Object ext) { P p = new P(); return p == ext; }
        public static boolean testTwoAliases() { P p = new P(); P q = p; return p == q; }
        public static boolean testFieldChangeAroundCompare() {
            P p = new P();
            p.x = 1;
            boolean r1 = (p == p);
            p.x = 2;
            boolean r2 = (p == p);
            return r1 && r2;
        }
        // a escapes (materialized); b stays virtual; a == b is false.
        public static boolean testVirtualVsMaterialized() {
            P a = new P();
            sink = a;
            P b = new P();
            return a == b;
        }
    }
}
