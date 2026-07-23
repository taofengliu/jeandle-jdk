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
 */

/*
 * @test
 * @summary PEA reconstructs virtual graphs at natural implicit exception traps
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEADeoptImplicitTrap
 */

package compiler.jeandle.pea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestPEADeoptImplicitTrap {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEADeoptImplicitTrap$TestWrapper";
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    public static void main(String[] args) throws Exception {
        Method nullTrap = TestWrapper.class.getMethod(
                "nullTrap", TestWrapper.External.class, int.class);
        Method checkcastTrap = TestWrapper.class.getMethod(
                "checkcastTrap", Object.class, int.class);
        Method boundsTrap = TestWrapper.class.getMethod(
                "boundsTrap", int[].class, int.class, int.class);
        Method[] targets = {nullTrap, checkcastTrap, boundsTrap};

        builder(false, targets).runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run = builder(true, targets).run()) {
            assertTrapShape(run, nullTrap, "null", true);
            assertTrapShape(run, checkcastTrap, "checkcast", false);
            assertTrapShape(run, boundsTrap, "bounds", false);
        }
    }

    private static PEATestUtils.RunBuilder builder(boolean shape, Method[] targets)
            throws ReflectiveOperationException {
        PEATestUtils.RunBuilder builder = shape ? PEATestUtils.shapeRun(WRAPPER, targets)
                : PEATestUtils.behaviorRun(WRAPPER, targets);
        return builder.inline(TestWrapper.class.getDeclaredMethod("graphRoot"))
                .inline(TestWrapper.class.getDeclaredMethod(
                        "graphArray", TestWrapper.Node.class,
                        TestWrapper.Node.class, TestWrapper.Node.class));
    }

    private static void assertTrapShape(PEATestUtils.RunResult run, Method target,
                                        String kind, boolean hasMonitor)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertConverged();
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.peaAllocCount(), 4,
                target + ": root, peer, shared child, and object array enter PEA");
        Asserts.assertEquals(after.occurrenceCount("@llvm.experimental.deoptimize"), 1,
                target + ": one exact natural " + kind + " trap");
        PEATestUtils.DeoptBundle bundle = after.deoptBundleAtCall(
                "llvm.experimental.deoptimize", 0);
        Asserts.assertEquals(bundle.scopes().size(), 1,
                target + ": trap has one active Java scope");
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(), bundle.rootScope().bci(),
                target + ": trap BCI is duplicated exactly");
        assertGraph(bundle, target, hasMonitor);

        PEATestUtils.IRBlock trapBlock =
                after.blockContaining("@llvm.experimental.deoptimize", 0);
        trapBlock.assertAbsent("@jeandle.new_");
        trapBlock.assertAbsent("store atomic i32");
        trapBlock.assertAbsent("store atomic i32 900");
    }

    private static void assertGraph(PEATestUtils.DeoptBundle bundle, Method target,
                                    boolean hasMonitor) throws Exception {
        bundle.assertVirtualObjectIds(0, 1, 2, 3);
        int valueOffset = offset("value");
        int leftOffset = offset("left");
        int sharedOffset = offset("shared");
        int base = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        int scale = Unsafe.ARRAY_OBJECT_INDEX_SCALE;

        Map<Integer, PEATestUtils.VirtualObjectDescriptor> nodes = new HashMap<>();
        PEATestUtils.VirtualObjectDescriptor array = null;
        for (PEATestUtils.VirtualObjectDescriptor descriptor : bundle.virtualObjects().values()) {
            if (descriptor.kind() == PEATestUtils.DescriptorKind.ARRAY) {
                Asserts.assertNull(array, target + ": exactly one object-array descriptor");
                array = descriptor;
                continue;
            }
            Asserts.assertEquals(descriptor.kind(), PEATestUtils.DescriptorKind.INSTANCE,
                    target + ": graph node descriptor kind");
            Asserts.assertEquals(descriptor.fields().keySet(),
                    Set.of(valueOffset, leftOffset, sharedOffset),
                    target + ": exact graph node field topology");
            int value = scalarValue(descriptor, valueOffset, target);
            Asserts.assertNull(nodes.put(value, descriptor),
                    target + ": graph node value is unique");
        }
        Asserts.assertNotNull(array, target + ": object-array descriptor");
        Asserts.assertEquals(nodes.keySet(), Set.of(101, 202, 303),
                target + ": exact graph node values at trap");
        Asserts.assertEquals(array.elements().keySet(),
                Set.of(base, base + scale, base + 2 * scale),
                target + ": exact object-array element topology");

        PEATestUtils.VirtualObjectDescriptor root = nodes.get(101);
        PEATestUtils.VirtualObjectDescriptor peer = nodes.get(202);
        PEATestUtils.VirtualObjectDescriptor shared = nodes.get(303);
        bundle.assertVORef(root.id(), leftOffset, peer.id());
        bundle.assertVORef(root.id(), sharedOffset, shared.id());
        bundle.assertVORef(peer.id(), leftOffset, root.id());
        bundle.assertVORef(peer.id(), sharedOffset, shared.id());
        bundle.assertVORef(shared.id(), leftOffset, root.id());
        bundle.assertVORef(shared.id(), sharedOffset, shared.id());
        bundle.assertVORef(array.id(), base, root.id());
        bundle.assertVORef(array.id(), base + scale, peer.id());
        bundle.assertVORef(array.id(), base + 2 * scale, shared.id());

        if (hasMonitor) {
            Asserts.assertEquals(bundle.rootScope().monitors().size(), 1,
                    target + ": one active monitor at the null trap");
            PEATestUtils.DeoptMonitor monitor = bundle.rootScope().monitors().get(0);
            Asserts.assertTrue(monitor.eliminated(),
                    target + ": monitor is represented as eliminated virtual state");
            Asserts.assertEquals(monitor.owner().kind(),
                    PEATestUtils.DeoptValueKind.VO_REF,
                    target + ": monitor owner is a typed VORef");
            Asserts.assertEquals(monitor.owner().virtualObjectId(), root.id(),
                    target + ": monitor owner retains root identity");
        } else {
            Asserts.assertEquals(bundle.rootScope().monitors().size(), 0,
                    target + ": no monitor in this trap state");
        }
    }

    private static int scalarValue(PEATestUtils.VirtualObjectDescriptor descriptor,
                                   int valueOffset, Method target) {
        PEATestUtils.VirtualObjectEntry value = descriptor.fields().get(valueOffset);
        Asserts.assertNotNull(value, target + ": graph node value field");
        Asserts.assertEquals(value.basicType(), PEATestUtils.DeoptBasicType.INT,
                target + ": graph node value type");
        Asserts.assertEquals(value.value().kind(), PEATestUtils.DeoptValueKind.SCALAR,
                target + ": graph node value is scalar");
        return Integer.parseInt(value.value().operand().substring("i32 ".length()));
    }

    private static int offset(String name) throws Exception {
        Field field = TestWrapper.Node.class.getDeclaredField(name);
        return Math.toIntExact(UNSAFE.objectFieldOffset(field));
    }

    public static class TestWrapper {
        private static int normalPaths;
        private static int nullCatches;
        private static int checkcastCatches;
        private static int boundsCatches;
        private static int monitorReacquires;

        public static class Node {
            int value;
            Node left;
            Node shared;
        }

        public static class External {
            int value;
        }

        public static class Accepted {
            int marker = 11;
        }

        public static class Rejected { }

        public static void main(String[] args) throws Exception {
            new Node();
            new External();
            new Accepted();
            new Rejected();
            long digest = 0x6A09E667F3BCC909L;
            digest = mix(digest, nullTrap(external(7), 1));
            digest = mix(digest, checkcastTrap(new Accepted(), 2));
            digest = mix(digest, boundsTrap(new int[] {17, 29, 43}, 1, 3));
            Asserts.assertEquals(normalPaths, 3, "warm normal paths");

            PEATestUtils.compileConfiguredTargetsAtLevel4();

            digest = mix(digest, nullTrap(null, 4));
            digest = mix(digest, checkcastTrap(new Rejected(), 5));
            digest = mix(digest, boundsTrap(new int[] {17, 29, 43}, 4, 6));
            Asserts.assertEquals(nullCatches, 1, "one natural null catch");
            Asserts.assertEquals(checkcastCatches, 1, "one natural checkcast catch");
            Asserts.assertEquals(boundsCatches, 1, "one natural bounds catch");
            Asserts.assertEquals(monitorReacquires, 1,
                    "null trap exits and reacquires its monitor once");
            digest = mix(digest, normalPaths);
            digest = mix(digest, nullCatches);
            digest = mix(digest, checkcastCatches);
            digest = mix(digest, boundsCatches);
            digest = mix(digest, monitorReacquires);
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        // Add future trap categories here only with a natural trigger, behavior path,
        // and exact typed bundle assertion like the three cases below.
        public static int nullTrap(External external, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                synchronized (root) {
                    root.value += external.value;
                    peer.value += array.length;
                }
                normalPaths++;
            } catch (NullPointerException expected) {
                nullCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
                if (Thread.holdsLock(root)) {
                    return Integer.MIN_VALUE + 1;
                }
                synchronized (root) {
                    if (!Thread.holdsLock(root)) {
                        return Integer.MIN_VALUE + 2;
                    }
                    root.value += 900;
                    monitorReacquires++;
                }
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int checkcastTrap(Object candidate, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                Accepted accepted = (Accepted) candidate;
                root.value += accepted.marker;
                peer.value += array.length;
                normalPaths++;
            } catch (ClassCastException expected) {
                checkcastCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        public static int boundsTrap(int[] values, int index, int seed) {
            Node root = graphRoot();
            Node peer = root.left;
            Node shared = root.shared;
            Object[] array = graphArray(root, peer, shared);
            try {
                root.value += values[index];
                peer.value += array.length;
                normalPaths++;
            } catch (ArrayIndexOutOfBoundsException expected) {
                boundsCatches++;
                mutateAfterTrap(root, peer, shared, array, seed);
            }
            return verifyAndDigest(root, peer, shared, array, seed);
        }

        private static Node graphRoot() {
            Node root = new Node();
            Node peer = new Node();
            Node shared = new Node();
            root.value = 101;
            peer.value = 202;
            shared.value = 303;
            root.left = peer;
            root.shared = shared;
            peer.left = root;
            peer.shared = shared;
            shared.left = root;
            shared.shared = shared;
            return root;
        }

        private static Object[] graphArray(Node root, Node peer, Node shared) {
            Object[] array = new Object[3];
            array[0] = root;
            array[1] = peer;
            array[2] = shared;
            return array;
        }

        private static void mutateAfterTrap(Node root, Node peer, Node shared,
                                            Object[] array, int seed) {
            if (array[0] != root || array[1] != peer || array[2] != shared
                    || root.left != peer || root.shared != shared
                    || peer.left != root || peer.shared != shared
                    || shared.left != root || shared.shared != shared) {
                throw new AssertionError("reconstructed graph identity");
            }
            root.value += seed;
            peer.value += seed * 2;
            shared.value += seed * 3;
        }

        private static int verifyAndDigest(Node root, Node peer, Node shared,
                                           Object[] array, int seed) {
            if (array[0] != root || array[1] != peer || array[2] != shared
                    || root.left != peer || root.shared != shared
                    || peer.left != root || peer.shared != shared
                    || shared.left != root || shared.shared != shared) {
                return Integer.MIN_VALUE + 3;
            }
            return root.value * 31 + peer.value * 17 + shared.value * 7 + seed;
        }

        private static External external(int value) {
            External external = new External();
            external.value = value;
            return external;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
