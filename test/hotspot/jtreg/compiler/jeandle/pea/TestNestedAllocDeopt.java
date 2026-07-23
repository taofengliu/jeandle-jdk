/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
 * License version 2 for more details (a copy is included in the LICENSE
 * file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA allocation-bundle scanning: a VO referenced only by another
 *          allocation invoke's deopt bundle is described in that allocation's
 *          frame state. This is structural allocation-invoke coverage only.
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestNestedAllocDeopt
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestNestedAllocDeopt {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestNestedAllocDeopt$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test", TestWrapper.Holder.class);
        Method sink = TestWrapper.class.getDeclaredMethod("sink", TestWrapper.B.class);
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .dontinline(sink)
                .run()) {
            assertAllocationBundle(run, target);
        }
    }

    private static void assertAllocationBundle(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertConverged();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        List<Integer> sourceBCIs = before.allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), 2,
                target + ": exact first and second source allocations");
        Asserts.assertEquals(new HashSet<>(sourceBCIs).size(), 2,
                target + ": source allocations have distinct BCIs");

        int secondBCI = sourceBCIs.get(1);
        String secondResult = exactAllocationResult(after, secondBCI, target);
        List<String> definitions = after.lines().stream()
                .filter(line -> line.startsWith(secondResult + " = ")).toList();
        Asserts.assertEquals(definitions.size(), 1,
                target + ": exact second allocation SSA definition");
        Asserts.assertTrue(definitions.get(0).contains("@jeandle.new_instance"),
                target + ": selected allocation has the exact instance callee");
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtAllocation(secondResult);
        Asserts.assertEquals(bundle.rootScope().bci(), secondBCI,
                target + ": selected allocation bundle BCI");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(), secondBCI,
                target + ": selected allocation bundle duplicated BCI");
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor first = bundle.virtualObject(0);
        Asserts.assertEquals(first.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                target + ": first allocation is described as an instance");
        PEATestUtils.VirtualObjectEntry value = first.fields().get(
                Math.toIntExact(UNSAFE.objectFieldOffset(
                        TestWrapper.A.class.getDeclaredField("x"))));
        Asserts.assertNotNull(value, target + ": first object field is in closure");
        Asserts.assertEquals(value.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": first object field is scalar");
        Asserts.assertEquals(value.value().operand(), "i32 10",
                target + ": first object field value");
        Asserts.assertEquals(after.allocationBCIs(), List.of(secondBCI),
                target + ": first NeverEscapes allocation is eliminated");
        Asserts.assertEquals(after.peaAllocCount(), 1,
                target + ": only the second source OrigAlloc remains");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": no replacement allocation is introduced");
    }

    private static String exactAllocationResult(
            PEATestUtils.IRBody body, int bci, Method target) {
        List<String> matches = body.allocationBCIsByResult().entrySet().stream()
                .filter(entry -> entry.getValue() == bci)
                .map(Map.Entry::getKey).toList();
        Asserts.assertEquals(matches.size(), 1,
                target + ": exact SSA result for second allocation BCI " + bci);
        return matches.get(0);
    }

    public static class TestWrapper {
        public static class Holder { public int h; }
        public static class A { public int x; public int y; }
        public static class B { }

        static B global; // opaque escape target
        // Not inlined (dontinline); passing b here makes it PartiallyEscapes.
        static void sink(B b) { global = b; }

        public static void main(String[] args) throws Exception {
            new Holder(); new A(); new B();
            PEATestUtils.compileConfiguredTargetsAtLevel4();
            Holder holder = new Holder();
            holder.h = 3;
            int result = test(holder);
            Asserts.assertEquals(result, 13);
            System.out.println("PEA-RESULT:" + result);
        }

        // a is live across b's retained allocation, so b's own bundle carries
        // a's descriptor while a remains virtual.
        public static int test(Holder inp) {
            A a = new A();
            a.x = 10;
            B b = new B();          // b's allocation bundle references a
            sink(b);                // b escapes (PartiallyEscapes)
            a.y = inp.h;            // null-check safepoint; a live
            return a.x + a.y;
        }
    }
}
