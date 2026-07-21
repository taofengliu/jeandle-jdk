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
 * @summary PEA merged-allocation identity (synthesizeCaseC / Case C): two
 *          branch allocations merge into one synthetic VO when identity is
 *          unobservable (only fields read), and MUST refuse the merge —
 *          materializing — whenever identity is observable (a shared allocation
 *          in one branch, a kept alias, or a different concrete class). The
 *          observable cases must return a branch-dependent (non-constant)
 *          result; folding them to a constant would be a silent miscompile.
 *          Targets catalog bug 5.4 (Case C identity gate).
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAMergedAllocationIdentity
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestPEAMergedAllocationIdentity {
    static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAMergedAllocationIdentity$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method mRead = TestWrapper.class.getMethod("testMergeReadField", boolean.class, int.class, int.class);
        Method mObs = TestWrapper.class.getMethod("testMergeIdentityObservable", boolean.class);
        Method mAlias = TestWrapper.class.getMethod("testMergeOneBranchAlias", boolean.class);
        Method mSel = TestWrapper.class.getMethod("testMergeTransitiveSelectAlias", boolean.class, boolean.class);
        Method mDiff = TestWrapper.class.getMethod("testMergeDifferentClass", boolean.class);
        Method[] all = {mRead, mObs, mAlias, mSel, mDiff};

        PEATestUtils.Run run = PEATestUtils.run(WRAPPER)
                .llvmOptions(PEATestUtils.peaLLVMOptionsClass(TestWrapper.class));
        for (Method m : all) {
            run.compileonly(m.getName());
        }
        OutputAnalyzer out = run.run();

        // Identity-unobservable merge: the two branch allocations are both
        // Identity-unobservable merge: the two branch allocations are
        // NeverEscape (eliminated). Assert via the PEA target.
        PEATestUtils.assertStats(out, mRead, 2, 0, 0);
        PEATestUtils.assertNeverEscapes(out, mRead);

        // Identity-observable via a shared allocation or a kept alias: PEA folds
        // the == to the branch condition (non-constant, matching Graal), so the
        // allocations are still eliminated.
        PEATestUtils.assertNeverEscapes(out, mObs);
        PEATestUtils.assertNeverEscapes(out, mAlias);

        // testMergeTransitiveSelectAlias (two selects, different conditions) and
        // testMergeDifferentClass materialize: PEA conservatively keeps the real
        // objects and computes identity at runtime (correct; a missed fold, not
        // a miscompile) / cannot merge different classes. Both allocations are
        // retained (asserted via the PEA target).
        PEATestUtils.assertAllocRetained(out, mSel, 2);
        PEATestUtils.assertAllocRetained(out, mDiff, 2);

        out.shouldContain("read: ok");
        out.shouldContain("observable: ok");
        out.shouldContain("alias: ok");
        out.shouldContain("select: ok");
        out.shouldContain("diff: ok");
    }

    public static class TestWrapper {
        public static class P { public int x; }
        public static class Q { public int y; }

        public static void main(String[] args) {
            new P(); // resolve
            new Q(); // resolve
            // read-field merge: result is u (c) or v (!c).
            Asserts.assertEquals(testMergeReadField(true, 11, 22), 11);
            Asserts.assertEquals(testMergeReadField(false, 11, 22), 22);
            System.out.println("read: ok");
            // identity observable (Graal testMergeAllocationsInt3): true iff c.
            Asserts.assertTrue(testMergeIdentityObservable(true));
            Asserts.assertFalse(testMergeIdentityObservable(false));
            System.out.println("observable: ok");
            // one branch keeps an alias: keep==phi is true iff c.
            Asserts.assertTrue(testMergeOneBranchAlias(true));
            Asserts.assertFalse(testMergeOneBranchAlias(false));
            System.out.println("alias: ok");
            // transitive alias via two selects with different conditions:
            // alias==phi is (c == d) over all four combinations.
            Asserts.assertTrue(testMergeTransitiveSelectAlias(true, true));
            Asserts.assertFalse(testMergeTransitiveSelectAlias(true, false));
            Asserts.assertFalse(testMergeTransitiveSelectAlias(false, true));
            Asserts.assertTrue(testMergeTransitiveSelectAlias(false, false));
            System.out.println("select: ok");
            // different concrete class: 1 (c) or 2 (!c).
            Asserts.assertEquals(testMergeDifferentClass(true), 1);
            Asserts.assertEquals(testMergeDifferentClass(false), 2);
            System.out.println("diff: ok");
        }

        // Identity UNOBSERVABLE: o is only field-read, never compared. Case C
        // merges the two branch allocations into one synthetic VO.
        public static int testMergeReadField(boolean c, int u, int v) {
            P o;
            if (c) { o = new P(); o.x = u; }
            else   { o = new P(); o.x = v; }
            return o.x;
        }

        // Identity OBSERVABLE (Graal testMergeAllocationsInt3): in the c-branch
        // p1 and p2 are the SAME allocation; in the !c-branch they are DISTINCT.
        // p1 == p2 must stay branch-dependent (true iff c), not fold.
        public static boolean testMergeIdentityObservable(boolean c) {
            P p1, p2;
            if (c) {
                P t = new P(); t.x = 1; p1 = t; p2 = t;
            } else {
                P t1 = new P(); t1.x = 2; p1 = t1;
                P t2 = new P(); t2.x = 3; p2 = t2;
            }
            return p1 == p2;
        }

        // Identity OBSERVABLE via an extra use: `keep` aliases `a`, so `a` is
        // not a single-use allocation -> Case C must refuse. keep==phi is true
        // iff c.
        public static boolean testMergeOneBranchAlias(boolean c) {
            P a = new P(); a.x = 1;
            P b = new P(); b.x = 2;
            P keep = a;                 // a now has a second use beyond the merge
            P phi = c ? a : b;
            return keep == phi;
        }

        // Identity OBSERVABLE via transitive select aliases: `a` and `b` each
        // feed TWO selects (alias and phi) with different conditions, so each
        // has uses beyond a single merge. alias==phi must resolve to (c==d),
        // not fold to a constant. (Catalog 5.4 transitive-alias concern.)
        public static boolean testMergeTransitiveSelectAlias(boolean c, boolean d) {
            P a = new P(); a.x = 1;
            P b = new P(); b.x = 2;
            P alias = d ? a : b;
            P phi = c ? a : b;
            return alias == phi;
        }

        // Structural incompatibility: P vs Q cannot merge -> Case C refused.
        public static int testMergeDifferentClass(boolean c) {
            Object o;
            if (c) { P p = new P(); p.x = 1; o = p; }
            else   { Q q = new Q(); q.y = 2; o = q; }
            if (o instanceof P) { return ((P) o).x; }
            return ((Q) o).y;
        }
    }
}
