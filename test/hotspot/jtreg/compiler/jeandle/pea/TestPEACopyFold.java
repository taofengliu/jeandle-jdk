/*
 * @test
 * @summary PEA folds jeandle.copy (System.arraycopy / Arrays.copyOf[Range] /
 *          reference-array clone) into virtual field state: scalarization,
 *          escape replay, active-frame deopt reconstruction with copied
 *          entries, and exception-path conservatism are behavior-equivalent
 *          with PEA off
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEACopyFold
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;

import jdk.test.lib.Asserts;

public class TestPEACopyFold {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEACopyFold$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method v2vInt = TestWrapper.class.getMethod("testArrayCopyIntV2V");
        Method v2vRef = TestWrapper.class.getMethod("testArrayCopyRefV2V");
        Method copyOf = TestWrapper.class.getMethod("testCopyOfString");
        Method copyOfRange = TestWrapper.class.getMethod("testCopyOfRangeString");
        Method overlap = TestWrapper.class.getMethod("testSelfOverlapInt");
        Method escape = TestWrapper.class.getMethod("testEscapeReplayInt");
        Method deoptV2V = TestWrapper.class.getMethod("testDeoptIntV2V");
        Method deoptConcrete = TestWrapper.class.getMethod("testDeoptRefConcrete");
        Method cloneRef = TestWrapper.class.getMethod("testCloneRefV2V");
        Method exceptions = TestWrapper.class.getMethod("testExceptions");
        Method requestDeopt = TestWrapper.class.getDeclaredMethod("requestDeopt");
        Method[] targets = {v2vInt, v2vRef, copyOf, copyOfRange, overlap,
                            escape, deoptV2V, deoptConcrete, cloneRef, exceptions};

        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(requestDeopt)
                .runPEAOnOffEquivalent();

        // Structural evidence that the fold actually engaged: per-round
        // CopyFolds statistics, a ReplaceCall effect on the pseudo call, and
        // no surviving @jeandle.copy in the final IR.
        // Structural evidence that the fold engages through the real
        // frontend. arraycopy folds for virtual and concrete sources.
        // copyOf folds the same way in profiled compilations (standalone
        // runs show CopyFolds=1 with a virtual source of constant length),
        // but the shape run's forced cold compilation neither inlines the
        // two-argument wrapper nor binds the intrinsic's three-argument
        // form, so no jeandle.copy appears there and the workload stays a
        // behavior oracle. clone-reference-array only appears with
        // ReduceInitialCardMarks disabled — under the default flags a
        // reference-array clone emits the byte-payload clone-array kind and
        // stays conservative, so testCloneRefV2V is likewise a behavior
        // oracle.
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, v2vInt, deoptConcrete)
                .dontinline(requestDeopt)
                .run()) {
            assertFold(run, v2vInt, 2);
            assertFold(run, deoptConcrete, 1);
        }
    }

    private static void assertFold(PEATestUtils.RunResult run, Method target,
                                   int neverEscapes) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        Asserts.assertTrue(report.round(0).hasStats(),
                target + ": stats present");
        Asserts.assertEquals(report.round(0).copyFolds(), 1,
                target + ": exactly one jeandle.copy folded");
        Asserts.assertEquals(report.round(0).neverEscapes(), neverEscapes,
                target + ": arrays never escape");
        Asserts.assertTrue(report.effects("ReplaceCall").stream()
                        .anyMatch(e -> e.detail().contains("jeandle.copy")),
                target + ": ReplaceCall effect on the pseudo call");
        run.finalIR(target).assertAbsent("@jeandle.copy");
    }

    public static class TestWrapper {
        private static final String[] SRC_STRINGS = {"aa", "bb", "cc", "dd"};
        private static final int[] SRC_INTS = {10, 20, 30, 40, 50, 60};
        private static int[] sinkInt;

        private static Method deoptTarget;

        public static void main(String[] args) throws Exception {
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            long payload = 0x6A09E667F3BCC909L;
            // A shape run configures only a subset of the targets; run
            // exactly the configured ones so every workload is compiled
            // before its deopt request.
            if (configured("testArrayCopyIntV2V")) {
                payload = mix(payload, testArrayCopyIntV2V());
            }
            if (configured("testArrayCopyRefV2V")) {
                payload = mix(payload, testArrayCopyRefV2V());
            }
            if (configured("testCopyOfString")) {
                payload = mix(payload, testCopyOfString());
            }
            if (configured("testCopyOfRangeString")) {
                payload = mix(payload, testCopyOfRangeString());
            }
            if (configured("testSelfOverlapInt")) {
                payload = mix(payload, testSelfOverlapInt());
            }
            if (configured("testEscapeReplayInt")) {
                payload = mix(payload, testEscapeReplayInt());
            }
            if (configured("testDeoptIntV2V")) {
                deoptTarget = TestWrapper.class.getMethod("testDeoptIntV2V");
                payload = mix(payload, testDeoptIntV2V());
            }
            if (configured("testDeoptRefConcrete")) {
                deoptTarget = TestWrapper.class.getMethod("testDeoptRefConcrete");
                payload = mix(payload, testDeoptRefConcrete());
            }
            if (configured("testCloneRefV2V")) {
                payload = mix(payload, testCloneRefV2V());
            }
            if (configured("testExceptions")) {
                payload = mix(payload, testExceptions());
            }

            System.out.println("PEA-RESULT:" + Long.toUnsignedString(payload, 16));
        }

        private static boolean configured(String methodName) {
            return System.getProperty(
                    "compiler.jeandle.pea.configuredTargets", "").contains(methodName);
        }

        // Virtual-to-virtual System.arraycopy over a primitive array, with an
        // interior range so untouched default slots are also observed.
        public static long testArrayCopyIntV2V() {
            int[] src = new int[8];
            for (int i = 0; i < 8; i++) {
                src[i] = (i + 1) * 11;
            }
            int[] dst = new int[8];
            System.arraycopy(src, 1, dst, 2, 5);
            long sum = 0;
            for (int i = 0; i < 8; i++) {
                sum = sum * 31 + dst[i];
            }
            return sum;
        }

        // Virtual-to-virtual System.arraycopy over a reference array; the
        // transferred entries keep element identity.
        public static long testArrayCopyRefV2V() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] dst = new String[4];
            System.arraycopy(src, 0, dst, 0, 4);
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (dst[i] != SRC_STRINGS[i] || dst[i].length() != 2) {
                    return Long.MIN_VALUE + 1;
                }
                sum = sum * 31 + dst[i].charAt(0);
            }
            return sum;
        }

        // Reference-array clone. Under default flags the frontend emits the
        // byte-payload clone-array kind, so this exercises the conservative
        // path's correctness (the clone-reference-array fold itself is
        // covered by the lit tests).
        public static long testCloneRefV2V() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] c = src.clone();
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (c[i] != SRC_STRINGS[i] || c.length != 4) {
                    return Long.MIN_VALUE + 10;
                }
                sum = sum * 31 + c[i].charAt(0);
            }
            return sum;
        }

        // Arrays.copyOf over a VIRTUAL source: the source's arraylength is
        // a compile-time constant, so the intrinsic's count expression
        // min(length, srcLength - start) folds and the copyof kind folds by
        // entry transfer in profiled compilations. A concrete source of
        // unknown length keeps the select and stays conservative (matching
        // Graal's constant-length requirement).
        public static long testCopyOfString() {
            String[] src = {"aa", "bb", "cc", "dd"};
            String[] copy = Arrays.copyOf(src, 4);
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (copy[i] != SRC_STRINGS[i]) {
                    return Long.MIN_VALUE + 2;
                }
                sum = sum * 31 + copy[i].charAt(1);
            }
            return sum;
        }

        public static long testCopyOfRangeString() {
            String[] copy = Arrays.copyOfRange(SRC_STRINGS, 1, 3);
            if (copy.length != 2 || copy[0] != SRC_STRINGS[1]
                    || copy[1] != SRC_STRINGS[2] || copy[0].length() != 2) {
                return Long.MIN_VALUE + 3;
            }
            return copy[0].charAt(0) * 31 + copy[1].charAt(0);
        }

        // Overlapping same-array copy toward higher indices (reverse-order
        // transfer).
        public static long testSelfOverlapInt() {
            int[] a = {1, 2, 3, 4, 5, 6};
            System.arraycopy(a, 0, a, 1, 4);
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                sum = sum * 31 + a[i];
            }
            return sum;
        }

        // The destination escapes AFTER the copy: the copy still folds, the
        // array materializes at the publication, and the published object
        // carries the copied values under a stable identity.
        public static long testEscapeReplayInt() {
            int[] dst = new int[6];
            System.arraycopy(SRC_INTS, 0, dst, 0, 6);
            sinkInt = dst;
            if (sinkInt != dst) {
                return Long.MIN_VALUE + 4;
            }
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                if (sinkInt[i] != SRC_INTS[i]) {
                    return Long.MIN_VALUE + 5;
                }
                sum = sum * 31 + sinkInt[i];
            }
            return sum;
        }

        // Active-frame deopt after a virtual-to-virtual copy: the deopt pool
        // must reconstruct the destination array with the TRANSFERRED entries.
        public static long testDeoptIntV2V() {
            int[] src = new int[6];
            for (int i = 0; i < 6; i++) {
                src[i] = 1000 + i;
            }
            int[] dst = new int[6];
            System.arraycopy(src, 0, dst, 0, 6);

            requestDeopt();

            if (dst.length != 6) {
                return Long.MIN_VALUE + 6;
            }
            long sum = 0;
            for (int i = 0; i < 6; i++) {
                if (dst[i] != 1000 + i) {
                    return Long.MIN_VALUE + 7;
                }
                sum = sum * 31 + dst[i];
            }
            return sum;
        }

        // Active-frame deopt after a concrete-source copy: the deopt pool
        // reconstructs the destination from the element-load entries, and the
        // reconstructed elements keep identity with the source's elements.
        public static long testDeoptRefConcrete() {
            String[] dst = new String[4];
            System.arraycopy(SRC_STRINGS, 0, dst, 0, 4);

            requestDeopt();

            if (dst.length != 4) {
                return Long.MIN_VALUE + 8;
            }
            long sum = 0;
            for (int i = 0; i < 4; i++) {
                if (dst[i] != SRC_STRINGS[i]) {
                    return Long.MIN_VALUE + 9;
                }
                sum = sum * 31 + dst[i].charAt(0);
            }
            return sum;
        }

        // The exception shapes must survive the optimization: null, bounds,
        // and store-check failures are observed identically.
        public static long testExceptions() {
            long caught = 0;
            try {
                System.arraycopy(null, 0, new int[1], 0, 1);
            } catch (NullPointerException e) {
                caught += 1;
            }
            try {
                System.arraycopy(new int[4], 2, new int[4], 0, 3);
            } catch (ArrayIndexOutOfBoundsException e) {
                caught += 10;
            }
            try {
                System.arraycopy(new Integer[]{1}, 0, new String[1], 0, 1);
            } catch (ArrayStoreException e) {
                caught += 100;
            }
            try {
                System.arraycopy(new int[2], 0, new int[2], 0, -1);
            } catch (ArrayIndexOutOfBoundsException e) {
                caught += 1000;
            }
            return caught;
        }

        private static void requestDeopt() {
            PEATestUtils.ActiveFrameDeoptEvidence evidence =
                    PEATestUtils.deoptimizeActiveFrame(deoptTarget, 2);
            if (!evidence.frameDeoptimized()
                    || evidence.compilationLevel() != 4
                    || evidence.markedNMethods() != 1) {
                throw new AssertionError("exact active-frame deopt evidence");
            }
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13)
                    * 0x9E3779B97F4A7C15L;
        }
    }
}
