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
 * @summary PEA cyclic object graphs: read-only self/A-B/three-node cycles are
 *          NeverEscape (allocation eliminated, traversal folds); a cycle edge
 *          overwritten before escape still resolves correctly; a cycle that
 *          escapes from one node materializes the whole reachable closure
 *          (each OrigAlloc retained once, field replay, no infinite recursion,
 *          no duplicate materialization); and a live cycle reconstructed at a
 *          deopt safepoint terminates (cycle-closure in the VO descriptor).
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEACyclicObjectGraph
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestPEACyclicObjectGraph {
    static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACyclicObjectGraph$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method mSelf = TestWrapper.class.getMethod("testSelfCycleReadOnly");
        Method mAB = TestWrapper.class.getMethod("testABCycle");
        Method mThree = TestWrapper.class.getMethod("testThreeNodeCycle");
        Method mOver = TestWrapper.class.getMethod("testCycleEdgeOverwritten");
        Method mEsc = TestWrapper.class.getMethod("testCycleEscapesFromRoot");
        Method mDeopt = TestWrapper.class.getMethod("testCycleDeoptRoot", TestWrapper.Node.class);
        Method[] all = {mSelf, mAB, mThree, mOver, mEsc, mDeopt};

        PEATestUtils.Run run = PEATestUtils.run(WRAPPER)
                .llvmOptions(PEATestUtils.peaLLVMOptionsClass(TestWrapper.class))
                .printNMethods(true);
        for (Method m : all) {
            run.compileonly(m.getName());
        }
        OutputAnalyzer out = run.run();

        // Read-only cycles + overwritten edge + deopt-root: NeverEscape. The VO
        // count equals the number of distinct nodes (self=1, A-B=2, three=3).
        PEATestUtils.assertStats(out, mSelf, 1, 0, 0);
        PEATestUtils.assertStats(out, mAB, 2, 0, 0);
        PEATestUtils.assertStats(out, mThree, 3, 0, 0);
        PEATestUtils.assertStats(out, mOver, 1, 0, 0);
        PEATestUtils.assertStats(out, mDeopt, 2, 0, 0);
        for (Method m : new Method[] {mSelf, mAB, mThree, mOver, mDeopt}) {
            PEATestUtils.assertNeverEscapes(out, m);
        }
        // Escape from one node materializes the whole 2-node closure exactly
        // once: both @jeandle.new_instance are retained after PEA (no duplicate
        // materialization, which would show >2).
        PEATestUtils.assertAllocRetained(out, mEsc, 2);

        out.shouldContain("self: true");
        out.shouldContain("ab: true");
        out.shouldContain("three: true");
        out.shouldContain("overwrite: ok");
        out.shouldContain("escape: ok");
        out.shouldContain("deopt: ok");
        out.shouldContain("deopt: NPE");
    }

    public static class TestWrapper {
        public static class Node { public Node left; public int x; }
        static Node sink; // forces a node (and its reachable closure) to materialize

        public static void main(String[] args) {
            new Node(); // resolve
            Asserts.assertTrue(testSelfCycleReadOnly());
            System.out.println("self: true");
            Asserts.assertTrue(testABCycle());
            System.out.println("ab: true");
            Asserts.assertTrue(testThreeNodeCycle());
            System.out.println("three: true");
            Asserts.assertEquals(testCycleEdgeOverwritten(), 1);
            System.out.println("overwrite: ok");
            Asserts.assertEquals(testCycleEscapesFromRoot(), 3);
            System.out.println("escape: ok");
            // Non-deopt path returns the cycle sum; null probe deopts at the
            // safepoint with the cycle live (reconstruction must terminate).
            Node probe = new Node(); probe.x = 10;
            Asserts.assertEquals(testCycleDeoptRoot(probe), 13);
            System.out.println("deopt: ok");
            try {
                testCycleDeoptRoot(null);
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("deopt: NPE");
            }
        }

        // Self-cycle, read-only: n.left = n; traversal returns to n.
        public static boolean testSelfCycleReadOnly() {
            Node n = new Node(); n.x = 42; n.left = n;
            return n.left == n;
        }

        // A<->B cycle: a.left=b, b.left=a; two steps return to a.
        public static boolean testABCycle() {
            Node a = new Node(); a.x = 1;
            Node b = new Node(); b.x = 2;
            a.left = b; b.left = a;
            return a.left.left == a;
        }

        // A->B->C->A cycle: three steps return to a.
        public static boolean testThreeNodeCycle() {
            Node a = new Node(); Node b = new Node(); Node c = new Node();
            a.left = b; b.left = c; c.left = a;
            return a.left.left.left == a;
        }

        // A self-cycle edge overwritten before read: the final value wins.
        public static int testCycleEdgeOverwritten() {
            Node a = new Node(); a.x = 1;
            a.left = a;        // self-cycle
            a.left = null;     // overwritten
            return a.left == null ? 1 : 0;
        }

        // The cycle escapes from `a` (published): the whole closure (a, b)
        // materializes once, field values replayed, identity preserved.
        public static int testCycleEscapesFromRoot() {
            Node a = new Node(); a.x = 1;
            Node b = new Node(); b.x = 2;
            a.left = b; b.left = a;
            sink = a;          // a (and b reachable from a) escape
            return a.x + b.x;  // 1 + 2
        }

        // A live cycle across a null-check safepoint: with a non-null probe the
        // cycle sum is returned; with a null probe the frame deopts with the
        // cycle live, exercising cyclic VO-descriptor closure.
        public static int testCycleDeoptRoot(Node probe) {
            Node a = new Node(); a.x = 1;
            Node b = new Node(); b.x = 2;
            a.left = b; b.left = a;
            int x = probe.x;          // null-check safepoint; a, b live
            return a.x + b.x + x;     // 1 + 2 + probe.x
        }
    }
}
