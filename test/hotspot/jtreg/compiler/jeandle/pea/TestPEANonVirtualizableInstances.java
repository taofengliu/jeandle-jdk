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
 * @summary PEA virtualizes volatile-only state but conservatively retains
 *          finalizable, Thread, Reference, identity, and unknown-call objects
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEANonVirtualizableInstances
 */

package compiler.jeandle.pea;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;

public class TestPEANonVirtualizableInstances {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEANonVirtualizableInstances$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method volatileOnly = TestWrapper.class.getMethod("testVolatileOnly", int.class, int.class);
        Method finalizable = TestWrapper.class.getMethod("testFinalizable", int.class);
        Method thread = TestWrapper.class.getMethod("testThreadLifecycle", int.class);
        Method weakFactory = TestWrapper.class.getMethod("makeWeakReference", Object.class);
        Method identity = TestWrapper.class.getMethod("testIdentitySensitive", int.class);
        Method unknown = TestWrapper.class.getMethod("testUnknownCall", int.class);
        Method valueBased = TestWrapper.class.getMethod("testValueBasedMonitor", int.class);
        Method throwing = TestWrapper.class.getMethod("testThrowingConstructor", int.class);
        Method unknownConsumer = TestWrapper.class.getMethod("unknownConsumer",
                TestWrapper.PlainBox.class);
        Method threadRunner = TestWrapper.class.getMethod("startAndJoin",
                TestWrapper.TinyThread.class);
        Constructor<TestWrapper.FinalizableBox> finalizableCtor =
                TestWrapper.FinalizableBox.class.getDeclaredConstructor();
        Constructor<TestWrapper.TinyThread> threadCtor =
                TestWrapper.TinyThread.class.getDeclaredConstructor(int.class);
        Constructor<TestWrapper.ThrowingBox> throwingCtor =
                TestWrapper.ThrowingBox.class.getDeclaredConstructor(int.class);
        Method[] targets = {volatileOnly, finalizable, thread, weakFactory, identity,
                unknown, valueBased, throwing};

        behaviorBuilder(targets, unknownConsumer, threadRunner, weakFactory,
                finalizableCtor, threadCtor, throwingCtor)
                .runPEAOnOffEquivalent();
        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, unknownConsumer, threadRunner, weakFactory,
                        finalizableCtor, threadCtor, throwingCtor).run()) {
            assertVolatileVirtualized(run, volatileOnly);
            assertEligibilityGates(run, finalizable, identity, unknown);
            assertTinyThreadRetained(run, thread);
            assertWeakReferenceRetainedByDeoptimization(run, weakFactory);
        }
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets, Method unknownConsumer,
                                                         Method threadRunner, Method weakFactory,
                                                         Constructor<?> finalizableCtor,
                                                         Constructor<?> threadCtor,
                                                         Constructor<?> throwingCtor) {
        return configure(PEATestUtils.shapeRun(WRAPPER, targets), unknownConsumer,
                threadRunner, weakFactory,
                finalizableCtor, threadCtor, throwingCtor);
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method unknownConsumer,
                                                            Method threadRunner, Method weakFactory,
                                                            Constructor<?> finalizableCtor,
                                                            Constructor<?> threadCtor,
                                                            Constructor<?> throwingCtor) {
        return configure(PEATestUtils.behaviorRun(WRAPPER, targets), unknownConsumer,
                threadRunner, weakFactory,
                finalizableCtor, threadCtor, throwingCtor);
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
                                                      Method unknownConsumer,
                                                      Method threadRunner, Method weakFactory,
                                                      Constructor<?> finalizableCtor,
                                                      Constructor<?> threadCtor,
                                                      Constructor<?> throwingCtor) {
        return builder
                .dontinline(unknownConsumer)
                .dontinline(threadRunner)
                .dontinline(weakFactory)
                .compileOnly(finalizableCtor)
                .compileOnly(threadCtor)
                .compileOnly(throwingCtor);
    }

    private static void assertVolatileVirtualized(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(first.neverEscapes(), 1, target + ": volatile object is virtual");
        Asserts.assertEquals(before.allocations().size(), 1, target + ": source allocation");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.INSTANCE, target + ": volatile allocation kind");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": allocation elimination");
        Asserts.assertTrue(first.effectCount("EliminateStore") >= 2,
                target + ": volatile writes are represented in virtual state");
        Asserts.assertTrue(first.effectCount("ReplaceLoad") >= 2,
                target + ": volatile read and allocation guard are replaced");
        after.assertRetainsExactlyOriginalAllocations(before);
        after.assertAbsent("store atomic i32");
        after.assertAbsent("load atomic i32");
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no allocation remains after volatile scalar replacement");
    }

    private static void assertOriginalAllocationRetained(PEATestUtils.RunResult run,
                                                           Method target) throws Exception {
        PEATestUtils.IRBody before = run.report(target).round(0).before();
        PEATestUtils.IRBody after = run.report(target).finalAfter();
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one allocation subject to the eligibility gate");
        PEATestUtils.AllocationKey original = before.allocations().get(0).key();
        Asserts.assertEquals(original.kind(), PEATestUtils.AllocationKind.INSTANCE,
                target + ": eligibility-gated allocation kind");
        after.assertRetainsExactlyOriginalAllocations(before, original);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 1,
                target + ": exactly the original allocation survives lowering");
    }

    private static void assertEligibilityGates(PEATestUtils.RunResult run, Method... targets)
            throws Exception {
        List<String> failures = new ArrayList<>();
        for (Method target : targets) {
            try {
                assertOriginalAllocationRetained(run, target);
            } catch (AssertionError | RuntimeException failure) {
                failures.add(eligibilityEvidence(run, target, (Throwable) failure));
            }
        }
        if (!failures.isEmpty()) {
            throw new RuntimeException("PEA instance eligibility failures:\n"
                    + String.join("\n", failures));
        }
    }

    private static void assertTinyThreadRetained(PEATestUtils.RunResult run, Method target)
            throws Exception {
        PEATestUtils.IRBody before = run.report(target).round(0).before();
        PEATestUtils.AllocationKey tinyThread = new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 4);
        long sourceMatches = before.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).count();
        Asserts.assertEquals(sourceMatches, 1L,
                target + ": exactly one TinyThread source allocation at bci 4");

        PEATestUtils.IRBody after = run.report(target).finalAfter();
        long retainedMatches = after.allocations().stream()
                .filter(site -> site.key().equals(tinyThread)).count();
        Asserts.assertEquals(retainedMatches, 1L,
                target + ": the original TinyThread allocation is retained exactly once");
        for (PEATestUtils.AllocationSite retained : after.allocations()) {
            Asserts.assertTrue(before.allocations().stream()
                            .map(PEATestUtils.AllocationSite::key)
                            .anyMatch(retained.key()::equals),
                    target + ": PEA may retain only source allocations: " + retained.key());
        }
    }

    private static void assertWeakReferenceRetainedByDeoptimization(PEATestUtils.RunResult run,
                                                                     Method target) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        Asserts.assertEquals(first.before().allocations().size(), 0,
                target + ": Reference allocation is excluded before PEA");
        Asserts.assertEquals(first.effects().size(), 0,
                target + ": PEA does not transform an excluded Reference allocation");

        PEATestUtils.IRBody frontend = run.frontendIR(target);
        Asserts.assertEquals(frontend.peaAllocCount(), 0,
                target + ": frontend retains the Reference allocation outside PEA form");
        Asserts.assertEquals(frontend.loweredAllocCount(), 0,
                target + ": Reference allocation is not lowered into compiled code");
        Asserts.assertEquals(frontend.callOccurrencesAtBCI(
                        "llvm.experimental.deoptimize.p1", 0), List.of(0),
                target + ": original Reference allocation deoptimizes at its source bci");

        PEATestUtils.IRBody lowered = run.finalIR(target);
        Asserts.assertEquals(lowered.loweredAllocCount(), 0,
                target + ": lowering must not synthesize a compiled Reference allocation");
        Asserts.assertEquals(lowered.callOccurrencesAtBCI(
                        "llvm.experimental.gc.statepoint.p0", 0), List.of(0),
                target + ": lowered deoptimization preserves the original allocation bci");
    }

    private static String eligibilityEvidence(PEATestUtils.RunResult run, Method target,
                                              Throwable failure) throws Exception {
        PEATestUtils.PEARound first = run.report(target).round(0);
        String sources = first.before().allocations().stream()
                .map(PEATestUtils.AllocationSite::instruction)
                .reduce((left, right) -> left + " || " + right).orElse("none");
        String effects = first.effects().stream().map(PEATestUtils.PEAEffect::kind)
                .reduce((left, right) -> left + "," + right).orElse("none");
        return target + ": " + failure.getMessage()
                + " | round0 Never=" + first.neverEscapes()
                + " Partial=" + first.partiallyEscapes()
                + " Always=" + first.alwaysEscapes()
                + " | source=" + sources
                + " | effects=" + effects;
    }

    public static class TestWrapper {
        private static PlainBox observed;
        private static int threadValue;
        private static int constructorCalls;
        private static volatile int finalizerProbe;

        public static class VolatileBox {
            volatile int value;
        }

        @SuppressWarnings({"deprecation", "removal"})
        public static class FinalizableBox {
            int value;

            @Override
            @SuppressWarnings("deprecation")
            protected void finalize() {
                finalizerProbe = value;
            }
        }

        public static class TinyThread extends Thread {
            private final int value;

            TinyThread(int value) {
                this.value = value;
            }

            @Override
            public void run() {
                threadValue = value;
            }
        }

        public static class PlainBox {
            int value;
        }

        public static class TestWeakReference<T> extends WeakReference<T> {
            TestWeakReference(T value) {
                super(value);
            }
        }

        public static class ThrowingBox {
            final int value;

            ThrowingBox(int value) {
                constructorCalls++;
                if (value < 0) {
                    throw new ConstructorFailure(value);
                }
                this.value = value;
            }
        }

        public static class ConstructorFailure extends RuntimeException {
            final int value;

            ConstructorFailure(int value) {
                this.value = value;
            }
        }

        public static void main(String[] args) throws Exception {
            new VolatileBox();
            new FinalizableBox();
            Asserts.assertEquals(FinalizableBox.class.getDeclaredMethod("finalize")
                    .getDeclaringClass(), FinalizableBox.class,
                    "finalizable class overrides Object.finalize");
            new TinyThread(0);
            new PlainBox();
            try {
                new ThrowingBox(-1);
            } catch (ConstructorFailure expected) {
            }
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object strong = new Object();
            long digest = 0xC2B2AE3D27D4EB4FL;
            for (int value : new int[] {0, 7, -29, 0x12345678}) {
                Asserts.assertEquals(testVolatileOnly(value, value ^ 0x55AA55AA),
                        value ^ 0x55AA55AA, "volatile field value");
                Asserts.assertEquals(testFinalizable(value), value * 3 + 1,
                        "finalizable object state");
                Asserts.assertEquals(testThreadLifecycle(value), value * 5 + 3,
                        "Thread start/join lifecycle");
                Asserts.assertEquals(testWeakReference(strong), 15,
                        "WeakReference get/refersTo/clear behavior");
                Asserts.assertEquals(testIdentitySensitive(value), value + 1,
                        "identity hash remains stable for the same object");
                observed = null;
                Asserts.assertEquals(testUnknownCall(value), value + 9,
                        "unknown call observes and preserves object identity");
                Asserts.assertNotEquals(observed, null, "unknown call receives its argument");
                Asserts.assertEquals(observed.value, value + 9,
                        "post-call mutation is visible through the escaped identity");
                Asserts.assertEquals(testValueBasedMonitor(value), value ^ 0x5A5A5A5A,
                        "value-based monitor check preserves monitor semantics");
                constructorCalls = 0;
                int successfulValue = Math.abs(value);
                Asserts.assertEquals(testThrowingConstructor(successfulValue), successfulValue,
                        "successful constructor result");
                Asserts.assertEquals(constructorCalls, 1, "successful constructor count");
                constructorCalls = 0;
                Asserts.assertEquals(testThrowingConstructor(-successfulValue - 1),
                        successfulValue + 1,
                        "throwing constructor exception value");
                Asserts.assertEquals(constructorCalls, 1, "throwing constructor count");
                digest = mix(digest, value);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static int testVolatileOnly(int first, int second) {
            VolatileBox value = new VolatileBox();
            value.value = first;
            value.value = second;
            return value.value;
        }

        public static int testFinalizable(int value) {
            FinalizableBox box = new FinalizableBox();
            box.value = value;
            return box.value * 3 + 1;
        }

        public static int testThreadLifecycle(int value) throws InterruptedException {
            threadValue = 0;
            TinyThread thread = new TinyThread(value);
            return startAndJoin(thread);
        }

        public static int startAndJoin(TinyThread thread) throws InterruptedException {
            thread.start();
            thread.join();
            return threadValue * 5 + 3;
        }

        public static int testWeakReference(Object strong) {
            TestWeakReference<Object> reference = makeWeakReference(strong);
            boolean getBeforeClear = reference.get() == strong;
            boolean refersBeforeClear = reference.refersTo(strong);
            reference.clear();
            boolean getAfterClear = reference.get() == null;
            boolean refersAfterClear = reference.refersTo(null);
            return (getBeforeClear ? 1 : 0)
                    | (refersBeforeClear ? 2 : 0)
                    | (getAfterClear ? 4 : 0)
                    | (refersAfterClear ? 8 : 0);
        }

        public static TestWeakReference<Object> makeWeakReference(Object strong) {
            return new TestWeakReference<>(strong);
        }

        public static int testIdentitySensitive(int value) {
            PlainBox box = new PlainBox();
            box.value = value;
            int first = System.identityHashCode(box);
            int second = System.identityHashCode(box);
            return box.value + (first == second ? 1 : -1);
        }

        public static int testUnknownCall(int value) {
            PlainBox box = new PlainBox();
            box.value = value;
            unknownConsumer(box);
            box.value += 9;
            return observed == box ? observed.value : Integer.MIN_VALUE;
        }

        public static void unknownConsumer(PlainBox box) {
            observed = box;
        }

        @SuppressWarnings({"removal", "synchronization"})
        public static int testValueBasedMonitor(int value) {
            Integer boxed = new Integer(value);
            synchronized (boxed) {
                return boxed.intValue() ^ 0x5A5A5A5A;
            }
        }

        public static int testThrowingConstructor(int value) {
            try {
                return new ThrowingBox(value).value;
            } catch (ConstructorFailure expected) {
                return -expected.value;
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 11) * 0x9E3779B97F4A7C15L;
        }
    }
}
