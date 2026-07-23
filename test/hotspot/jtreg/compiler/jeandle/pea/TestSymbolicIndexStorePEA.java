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
 * @summary PEA materializes virtual arrays exactly once at symbolic-index
 *          loads and stores while preserving bounds, replay, and nested state
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestSymbolicIndexStorePEA
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestSymbolicIndexStorePEA {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestSymbolicIndexStorePEA$TestWrapper";
    private static final String BOUNDS_COMPARE = "icmp ult i32";
    private static final String DEOPTIMIZE = "@llvm.experimental.deoptimize";
    private static final String DEOPTIMIZE_I64 = "llvm.experimental.deoptimize.i64";
    private static final String DEOPTIMIZE_I64_CALL = "@" + DEOPTIMIZE_I64;
    private static final String LOWERED_DEOPTIMIZE = "@__llvm_deoptimize";

    public static void main(String[] args) throws Exception {
        Method store = TestWrapper.class.getMethod(
                "testSymbolicStore", int.class, int.class);
        Method load = TestWrapper.class.getMethod(
                "testSymbolicLoad", int.class);
        Method loadThenStore = TestWrapper.class.getMethod(
                "testLoadThenStore", int.class, int.class);
        Method twoIndexes = TestWrapper.class.getMethod(
                "testTwoIndexes", int.class, int.class, int.class);
        Method nested = TestWrapper.class.getMethod(
                "testNestedSymbolicStore", int.class, int.class);
        Method[] targets = {store, load, loadThenStore, twoIndexes, nested};

        PEATestUtils.behaviorRun(WRAPPER, targets).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets).run()) {
            assertSymbolicStore(run, store);
            assertSymbolicLoad(run, load);
            assertLoadThenStore(run, loadThenStore);
            assertTwoIndexes(run, twoIndexes);
            assertNestedStore(run, nested);
        }
    }

    private static void assertSymbolicStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 27), target);
        assertFinalBoundsFallback(run.finalIR(target), 1);
        PEATestUtils.IRBlock consumer = after.blockContaining("store atomic", 0);
        consumer.assertOccurrenceCount("store atomic", 5);
        for (int replay = 0; replay < 4; replay++) {
            consumer.assertBefore("store atomic", replay, "store atomic", 4);
        }
        assertNoAllocationAtConsumer(consumer);
        assertNormalPathHasNoDeopt(run, target, consumer, "store atomic", 0);
    }

    private static void assertSymbolicLoad(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 26), target);
        assertFinalBoundsFallback(run.finalIR(target), 1);
        PEATestUtils.IRBlock consumer = after.blockContaining("load atomic", 0);
        consumer.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            consumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        assertNoAllocationAtConsumer(consumer);
        assertNormalPathHasNoDeopt(run, target, consumer, "store atomic", 0);
    }

    private static void assertLoadThenStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 26), target);
        assertFinalBoundsFallback(run.finalIR(target), 1);
        PEATestUtils.IRBlock firstConsumer = after.blockContaining("load atomic", 0);
        firstConsumer.assertOccurrenceCount("store atomic", 5);
        for (int replay = 0; replay < 4; replay++) {
            firstConsumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        firstConsumer.assertBefore("load atomic", 0, "store atomic", 4);
        assertNoAllocationAtConsumer(firstConsumer);
        assertNormalPathHasNoDeopt(run, target, firstConsumer, "store atomic", 0);
    }

    private static void assertTwoIndexes(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 1);
        PEATestUtils.IRBody after = report.finalAfter();
        assertIntArrayDescriptor(assertBoundsFallback(after, 0, 26), target);
        PEATestUtils.DeoptBundle secondFallback =
                assertBoundsFallback(after, 1, 32);
        Asserts.assertEquals(secondFallback.virtualObjects().size(), 0,
                target + ": second bounds fallback uses the first materialization");
        assertFinalBoundsFallback(run.finalIR(target), 2);

        PEATestUtils.IRBlock loadConsumer = after.blockContaining("load atomic", 0);
        loadConsumer.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            loadConsumer.assertBefore("store atomic", replay, "load atomic", 0);
        }
        assertNoAllocationAtConsumer(loadConsumer);

        PEATestUtils.IRBlock secondBounds = after.blockContaining(BOUNDS_COMPARE, 1);
        secondBounds.assertOccurrenceCount("store atomic", 4);
        for (int replay = 0; replay < 4; replay++) {
            secondBounds.assertBefore("store atomic", replay, BOUNDS_COMPARE, 0);
        }
        secondBounds.assertAbsent("@jeandle.new_array");
        PEATestUtils.IRBlock storeConsumer = after.blockContaining("store atomic", 4);
        storeConsumer.assertOccurrenceCount("store atomic", 1);
        assertNoAllocationAtConsumer(storeConsumer);
        loadConsumer.assertAbsent(DEOPTIMIZE);
        storeConsumer.assertAbsent(DEOPTIMIZE);
        PEATestUtils.IRBody finalIR = run.finalIR(target);
        finalIR.blockContaining("store atomic", 0).assertAbsent(LOWERED_DEOPTIMIZE);
        finalIR.blockContaining("store atomic", 4).assertAbsent(LOWERED_DEOPTIMIZE);
        after.assertBefore("load atomic", 0, BOUNDS_COMPARE, 1);
        after.assertBefore(BOUNDS_COMPARE, 1, "store atomic", 4);
    }

    private static void assertNestedStore(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = assertOriginalMaterialization(run, target, 2);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();
        Asserts.assertEquals(before.allocations().stream()
                        .filter(site -> site.key().kind()
                                == PEATestUtils.AllocationKind.INSTANCE).count(),
                1L, target + ": one nested child source allocation");
        Asserts.assertEquals(before.allocations().stream()
                        .filter(site -> site.key().kind()
                                == PEATestUtils.AllocationKind.ARRAY).count(),
                1L, target + ": one nested array source allocation");
        PEATestUtils.DeoptBundle fallback = assertBoundsFallback(after, 0, 25);
        fallback.assertVirtualObjectIds(0, 1);
        Asserts.assertEquals(fallback.virtualObject(0).kind(),
                PEATestUtils.DescriptorKind.ARRAY,
                target + ": bounds fallback carries the nested array");
        Asserts.assertEquals(fallback.virtualObject(1).kind(),
                PEATestUtils.DescriptorKind.INSTANCE,
                target + ": bounds fallback carries the nested child");
        fallback.assertVORef(0, 24, 1);
        assertFinalBoundsFallback(run.finalIR(target), 1);
        PEATestUtils.IRBlock consumer = after.blockContaining("store atomic", 0);
        consumer.assertOccurrenceCount("store atomic", 3);
        consumer.assertBefore("store atomic", 0, "store atomic", 2);
        consumer.assertBefore("store atomic", 1, "store atomic", 2);
        assertNoAllocationAtConsumer(consumer);
        assertNormalPathHasNoDeopt(run, target, consumer, "store atomic", 0);
    }

    private static PEATestUtils.PEAReport assertOriginalMaterialization(
            PEATestUtils.RunResult run, Method target, int allocationCount) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), allocationCount,
                target + ": source allocation count");
        Asserts.assertEquals(first.partiallyEscapes(), allocationCount,
                target + ": symbolic use makes each reachable allocation partial");
        Asserts.assertEquals(first.effectCount("Materialize"), (long) allocationCount,
                target + ": one use-point materialization per reachable allocation");
        List<PEATestUtils.AllocationKey> keys =
                before.allocations().stream().map(PEATestUtils.AllocationSite::key).toList();
        after.assertRetainsExactlyOriginalAllocations(
                before, keys.toArray(PEATestUtils.AllocationKey[]::new));
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), allocationCount,
                target + ": lowering retains only the original allocations");
        return report;
    }

    private static PEATestUtils.DeoptBundle assertBoundsFallback(
            PEATestUtils.IRBody after, int compareOccurrence, int expectedBCI) {
        PEATestUtils.IRBlock bounds =
                after.blockContaining(BOUNDS_COMPARE, compareOccurrence);
        bounds.assertOccurrenceCount(BOUNDS_COMPARE, 1);
        bounds.assertOccurrenceCount("br i1", 1);
        bounds.assertAbsent(DEOPTIMIZE);
        if (compareOccurrence == 0) {
            bounds.assertAbsent("store atomic");
            bounds.assertAbsent("load atomic");
        }

        List<Integer> deopts = after.callOccurrencesAtBCI(
                DEOPTIMIZE_I64, expectedBCI);
        Asserts.assertEquals(deopts.size(), 1,
                after.methodId() + ": exact bounds fallback BCI " + expectedBCI);
        int occurrence = deopts.get(0);
        PEATestUtils.IRBlock fallback =
                after.blockContaining(DEOPTIMIZE_I64_CALL, occurrence);
        fallback.assertOccurrenceCount(DEOPTIMIZE_I64_CALL, 1);
        fallback.assertOccurrenceCount("ret i64", 1);
        fallback.assertAbsent("store atomic");
        fallback.assertAbsent("load atomic");
        after.assertBefore(
                BOUNDS_COMPARE, compareOccurrence, DEOPTIMIZE_I64_CALL, occurrence);
        if (compareOccurrence == 0) {
            after.assertBefore(BOUNDS_COMPARE, 0, "store atomic", 0);
        }
        return after.deoptBundleAtCall(DEOPTIMIZE_I64, occurrence);
    }

    private static void assertFinalBoundsFallback(
            PEATestUtils.IRBody finalIR, int expectedCount) {
        finalIR.assertOccurrenceCount(BOUNDS_COMPARE, expectedCount);
        finalIR.assertOccurrenceCount(LOWERED_DEOPTIMIZE, expectedCount);
        for (int occurrence = 0; occurrence < expectedCount; occurrence++) {
            PEATestUtils.IRBlock bounds =
                    finalIR.blockContaining(BOUNDS_COMPARE, occurrence);
            bounds.assertOccurrenceCount(BOUNDS_COMPARE, 1);
            bounds.assertOccurrenceCount("br i1", 1);
            bounds.assertAbsent(LOWERED_DEOPTIMIZE);

            PEATestUtils.IRBlock fallback =
                    finalIR.blockContaining(LOWERED_DEOPTIMIZE, occurrence);
            fallback.assertOccurrenceCount(LOWERED_DEOPTIMIZE, 1);
            fallback.assertAbsent("store atomic");
            fallback.assertAbsent("load atomic");
            finalIR.assertBefore(
                    BOUNDS_COMPARE, occurrence, LOWERED_DEOPTIMIZE, occurrence);
        }
    }

    private static void assertIntArrayDescriptor(
            PEATestUtils.DeoptBundle bundle, Method target) {
        bundle.assertVirtualObjectIds(0);
        PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(0);
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY,
                target + ": bounds fallback carries the virtual int[]");
        Asserts.assertEquals(array.elements().values().stream()
                        .map(entry -> entry.value().operand()).toList(),
                List.of("i32 11", "i32 22", "i32 33", "i32 44"),
                target + ": bounds fallback preserves exact pre-access elements");
    }

    private static void assertNormalPathHasNoDeopt(
            PEATestUtils.RunResult run, Method target,
            PEATestUtils.IRBlock afterConsumer,
            String finalConsumerNeedle, int finalConsumerOccurrence) throws Exception {
        afterConsumer.assertAbsent(DEOPTIMIZE);
        run.finalIR(target)
                .blockContaining(finalConsumerNeedle, finalConsumerOccurrence)
                .assertAbsent(LOWERED_DEOPTIMIZE);
    }

    private static void assertNoAllocationAtConsumer(PEATestUtils.IRBlock consumer) {
        consumer.assertAbsent("@jeandle.new_array");
        consumer.assertAbsent("@jeandle.new_instance");
    }

    public static class TestWrapper {
        private static final int[] INITIAL = {11, 22, 33, 44};

        public static class Node {
            int value;
        }

        public static void main(String[] args) throws Exception {
            new Node();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long digest = 0xA4093822299F31D0L;
            for (int index : new int[] {0, 2, 3, -1, 4}) {
                int value = 1000 + index;
                long store = testSymbolicStore(index, value);
                Asserts.assertEquals(store, expectedStore(index, value),
                        "symbolic store index " + index);
                digest = mix(digest, store);

                long load = testSymbolicLoad(index);
                Asserts.assertEquals(load, expectedLoad(index),
                        "symbolic load index " + index);
                digest = mix(digest, load);

                long loadThenStore = testLoadThenStore(index, value);
                Asserts.assertEquals(loadThenStore,
                        expectedTwoIndexes(index, index, value, 0x31),
                        "load-then-store index " + index);
                digest = mix(digest, loadThenStore);

                long nested = testNestedSymbolicStore(index, value);
                Asserts.assertEquals(nested, expectedNested(index, value),
                        "nested symbolic store index " + index);
                digest = mix(digest, nested);
            }

            int[][] pairs = {
                    {0, 0}, {2, 2}, {3, 3},
                    {0, 2}, {2, 3}, {3, 0},
                    {-1, 2}, {2, -1}, {4, 2}, {2, 4}
            };
            for (int[] pair : pairs) {
                int value = 2000 + pair[0] * 17 + pair[1];
                long actual = testTwoIndexes(pair[0], pair[1], value);
                long expected = expectedTwoIndexes(pair[0], pair[1], value, 0x53);
                Asserts.assertEquals(actual, expected,
                        "two symbolic indexes " + pair[0] + "," + pair[1]);
                digest = mix(digest, actual);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testSymbolicStore(int index, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                array[index] = value;
                return checksumValues(array[0], array[1], array[2], array[3], 0x17);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x71);
            }
        }

        public static long testSymbolicLoad(int index) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                return ((long) array[index] << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x29);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x92);
            }
        }

        public static long testLoadThenStore(int index, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                int old = array[index];
                array[index] = value;
                return ((long) old << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x31);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x13);
            }
        }

        public static long testTwoIndexes(int first, int second, int value) {
            int[] array = new int[4];
            array[0] = 11;
            array[1] = 22;
            array[2] = 33;
            array[3] = 44;
            try {
                int old = array[first];
                array[second] = value;
                return ((long) old << 32)
                        ^ checksumValues(array[0], array[1], array[2], array[3], 0x53);
            } catch (ArrayIndexOutOfBoundsException expected) {
                return checksumValues(array[0], array[1], array[2], array[3], 0x35);
            }
        }

        public static long testNestedSymbolicStore(int index, int value) {
            Node[] array = new Node[4];
            Node child = new Node();
            child.value = value;
            array[0] = child;
            try {
                array[index] = child;
                int identity = array[index] == child ? 1 : 0;
                identity |= array[0] == child ? 2 : 0;
                return ((long) child.value << 32) ^ ((long) array.length << 16)
                        ^ identity ^ 0x65;
            } catch (ArrayIndexOutOfBoundsException expected) {
                int unchanged = array[0] == child ? 1 : 0;
                unchanged |= array[1] == null ? 2 : 0;
                unchanged |= array[2] == null ? 4 : 0;
                unchanged |= array[3] == null ? 8 : 0;
                return ((long) child.value << 32) ^ ((long) array.length << 16)
                        ^ unchanged ^ 0x56;
            }
        }

        private static long expectedStore(int index, int value) {
            int[] expected = INITIAL.clone();
            if (valid(index)) {
                expected[index] = value;
                return checksum(expected, 0x17);
            }
            return checksum(expected, 0x71);
        }

        private static long expectedLoad(int index) {
            int[] expected = INITIAL.clone();
            if (valid(index)) {
                return ((long) expected[index] << 32) ^ checksum(expected, 0x29);
            }
            return checksum(expected, 0x92);
        }

        private static long expectedTwoIndexes(int first, int second, int value,
                                               int successMarker) {
            int[] expected = INITIAL.clone();
            if (!valid(first) || !valid(second)) {
                return checksum(expected, successMarker == 0x31 ? 0x13 : 0x35);
            }
            int old = expected[first];
            expected[second] = value;
            return ((long) old << 32) ^ checksum(expected, successMarker);
        }

        private static long expectedNested(int index, int value) {
            if (valid(index)) {
                return ((long) value << 32) ^ (4L << 16) ^ 3 ^ 0x65;
            }
            return ((long) value << 32) ^ (4L << 16) ^ 15 ^ 0x56;
        }

        private static boolean valid(int index) {
            return index >= 0 && index < INITIAL.length;
        }

        private static long checksum(int[] array, int marker) {
            return checksumValues(array[0], array[1], array[2], array[3], marker);
        }

        private static long checksumValues(int first, int second, int third, int fourth,
                                           int marker) {
            long value = marker;
            value = value * 257 + first;
            value = value * 257 + second;
            value = value * 257 + third;
            return value * 257 + fourth;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 17)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
