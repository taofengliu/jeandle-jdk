/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only,
 * as published by the Free Software Foundation.
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
 * @summary PEA outer iteration reaches an exact fixpoint after replay-enabled
 *          canonicalization and does not duplicate balanced lock replay
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAOuterIteration
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jdk.test.lib.Asserts;

public class TestPEAOuterIteration {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAOuterIteration$TestWrapper";
    private static final String MONITOR_ENTER = "@jeandle.monitorenter";
    private static final String MONITOR_EXIT = "@jeandle.monitorexit";
    private static final String FIELD_PHI = "pea.field.phi";
    private static final String CASE_C_FIELD_PHI = "pea.casec.field.phi";
    private static final String MATERIALIZED_PHI = "pea.materialized.phi";

    public static void main(String[] args) throws Exception {
        Method replayFold = TestWrapper.class.getMethod(
                "replayEnablesFolding", int.class);
        Method lockReplay = TestWrapper.class.getMethod(
                "balancedLockReplay", int.class);
        Method alreadyIdle = TestWrapper.class.getMethod(
                "alreadyIdle", int.class);
        Method loopRollback = TestWrapper.class.getMethod(
                "loopRollback", int.class, boolean.class, int.class);
        Method escape = TestWrapper.class.getMethod(
                "escape", TestWrapper.Box.class);
        Method[] targets = {replayFold, lockReplay, loopRollback, alreadyIdle};

        PEATestUtils.assertStructuralParserContracts();
        PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(escape)
                .runPEAOnOffEquivalent();

        ShapeRun cap1 = runShape(1, targets, escape);
        ShapeRun cap2 = runShape(2, targets, escape);
        ShapeRun cap4 = runShape(4, targets, escape);
        ShapeRun cap16 = runShape(16, targets, escape);

        assertReplayEnabledFolding(cap1.report(replayFold),
                cap2.report(replayFold), cap4.report(replayFold),
                cap16.report(replayFold), replayFold, escape);
        assertBalancedLockReplay(cap1.report(lockReplay),
                cap2.report(lockReplay), cap4.report(lockReplay),
                cap16.report(lockReplay), lockReplay);
        assertLoopRollback(cap1.report(loopRollback), cap2.report(loopRollback),
                cap4.report(loopRollback), cap16.report(loopRollback), loopRollback);
        assertAlreadyIdle(cap1.report(alreadyIdle), cap2.report(alreadyIdle),
                cap4.report(alreadyIdle), cap16.report(alreadyIdle), alreadyIdle);

        for (Method target : targets) {
            Asserts.assertEquals(cap16.summary(target), cap4.summary(target),
                    target + ": cap 4 and cap 16 have the exact stable summary");
        }
    }

    private static ShapeRun runShape(int cap, Method[] targets, Method escape)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, targets)
                .peaIterations(cap)
                .dontinline(escape)
                .run()) {
            PEATestUtils.PEAReport[] reports =
                    new PEATestUtils.PEAReport[targets.length];
            ShapeSummary[] summaries = new ShapeSummary[targets.length];
            for (int i = 0; i < targets.length; i++) {
                Method target = targets[i];
                PEATestUtils.PEAReport report = run.report(target);
                reports[i] = report;
                Asserts.assertTrue(report.roundCount() >= 1
                                && report.roundCount() <= cap,
                        target + ": exact rounds respect cap " + cap);
                for (PEATestUtils.PEARound round : report.rounds()) {
                    PEATestUtils.assertStructuralSoundness(round.before(),
                            target + ": cap " + cap + " round "
                                    + round.iteration() + " before");
                    PEATestUtils.assertStructuralSoundness(round.after(),
                            target + ": cap " + cap + " round "
                                    + round.iteration() + " after");
                    assertNoDuplicateEffectDetails(round, target, cap);
                }
                PEATestUtils.assertStructuralSoundness(report.finalAfter(),
                        target + ": cap " + cap + " final");
                if (cap >= 4) {
                    report.assertStoppedAtFixpoint();
                    report.assertFinalTransformIdle();
                }
                summaries[i] = ShapeSummary.of(report.finalAfter());
            }
            return new ShapeRun(targets, reports, summaries);
        }
    }

    private static void assertNoDuplicateEffectDetails(
            PEATestUtils.PEARound round, Method target, int cap) {
        Set<String> details = new HashSet<>();
        for (PEATestUtils.PEAEffect effect : round.effects()) {
            String identity = effect.kind() + "\n" + effect.detail();
            Asserts.assertTrue(details.add(identity),
                    target + ": cap " + cap + " round " + round.iteration()
                            + " repeats effect detail " + effect.kind()
                            + " " + effect.detail());
        }
    }

    private static void assertReplayEnabledFolding(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target, Method escape) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 4, 4);
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtIterationCap();

        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 2,
                target + ": guard and candidate source allocations");
        cap1.finalAfter().assertRetainsExactlyOriginalAllocations(
                input, allocations.get(1).key());
        cap2.finalAfter().assertRetainsExactlyOriginalAllocations(input);
        cap4.finalAfter().assertRetainsExactlyOriginalAllocations(input);

        String escapeName = PEATestUtils.MethodId.of(escape).llvmFunctionName();
        cap1.round(0).after().assertPresent("load atomic i32");
        cap1.round(0).after().assertPresent("br i1");
        cap1.round(0).after().assertLineCount("@\"" + escapeName + "\"", 1);
        Asserts.assertEquals(cap1.round(0).effectCount("Materialize", "[VO=1]"),
                2L, target + ": candidate replay covers escape and surviving arms");
        cap2.round(1).before().assertPresent("br i1 true");
        cap2.round(1).before().assertRetainsExactlyOriginalAllocations(
                input, allocations.get(1).key());
        Asserts.assertFalse(cap2.round(1).transformIdle(),
                target + ": round 2 removes the newly non-escaping candidate");
        Asserts.assertTrue(cap4.round(2).transformIdle(),
                target + ": first verification round is transform-idle");
        Asserts.assertTrue(cap4.round(3).transformIdle(),
                target + ": stable-delta verification round is transform-idle");
        cap2.round(1).after().assertAbsent("@\"" + escapeName + "\"");
        cap4.finalAfter().assertLineCount("store atomic i32", 0);
        cap4.finalAfter().assertLineCount("load atomic i32", 0);
        cap4.finalAfter().assertLineCount("br i1", 0);
        Asserts.assertEquals(ShapeSummary.of(cap2.finalAfter()),
                ShapeSummary.of(cap4.finalAfter()),
                target + ": productive round 2 already has stable IR shape");
    }

    private static void assertBalancedLockReplay(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 3, 3);
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtIterationCap();

        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 1,
                target + ": one source lock-owner allocation");
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    input, allocations.get(0).key());
        }
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": first round emits balanced replay");
        Asserts.assertTrue(cap2.round(1).transformIdle(),
                target + ": immediate repeated analysis is physically idle");
        Asserts.assertTrue(cap4.round(2).transformIdle(),
                target + ": final stable-delta probe remains idle");

        PEATestUtils.IRBody stable = cap4.finalAfter();
        stable.assertLineCount(MONITOR_ENTER, 1);
        Asserts.assertTrue(stable.lineCount(MONITOR_EXIT) >= 1,
                target + ": replayed enter has normal and exceptional exits");
        Asserts.assertEquals(cap1.finalAfter().lineCount(MONITOR_ENTER),
                stable.lineCount(MONITOR_ENTER),
                target + ": idle rounds do not repeat lock replay");
        Asserts.assertEquals(cap1.finalAfter().lineCount(MONITOR_EXIT),
                stable.lineCount(MONITOR_EXIT),
                target + ": idle rounds do not repeat balanced lock exits");
        stable.assertLineCount(MATERIALIZED_PHI, 0);
        stable.assertLineCount(FIELD_PHI, 0);
        stable.assertLineCount(CASE_C_FIELD_PHI, 0);
        Asserts.assertEquals(cap1.round(0).lockReplayPhysicalGroups().size(), 1,
                target + ": first round has one physical lock replay batch");
        Asserts.assertEquals(cap2.round(1).lockReplayPhysicalGroups().size(), 1,
                target + ": idle analysis repeats one replay plan");
        Asserts.assertEquals(cap4.round(2).lockReplayPhysicalGroups().size(), 1,
                target + ": stable analysis repeats one replay plan");
    }

    private static void assertAlreadyIdle(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 2, 2);
        cap1.assertStoppedAtIterationCap();
        cap2.assertStoppedAtFixpoint();
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            Asserts.assertEquals(report.round0Before().allocations().size(), 0,
                    target + ": idle method starts allocation-free");
            Asserts.assertTrue(report.rounds().stream()
                            .allMatch(PEATestUtils.PEARound::transformIdle),
                    target + ": every configured probe is transform-idle");
            Asserts.assertTrue(report.effects("Materialize").isEmpty(),
                    target + ": idle method has no replay effect");
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    report.round0Before());
        }
        Asserts.assertEquals(ShapeSummary.of(cap1.finalAfter()),
                ShapeSummary.of(cap16.finalAfter()),
                target + ": an already-idle method is a strict fixpoint");
    }

    private static void assertLoopRollback(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target) {
        assertRoundCounts(cap1, cap2, cap4, cap16, target, 1, 2, 3, 3);
        PEATestUtils.IRBody input = cap4.round0Before();
        List<PEATestUtils.AllocationSite> allocations = input.allocations();
        Asserts.assertEquals(allocations.size(), 1,
                target + ": one loop-carried source allocation");
        for (PEATestUtils.PEAReport report :
                List.of(cap1, cap2, cap4, cap16)) {
            report.finalAfter().assertRetainsExactlyOriginalAllocations(
                    input, allocations.get(0).key());
        }
        cap1.assertStoppedAtIterationCap();
        Asserts.assertFalse(cap1.round(0).transformIdle(),
                target + ": first round plans loop replay");
        Asserts.assertTrue(cap2.round(1).transformIdle(),
                target + ": immediate repeated loop transform is idle");
        Asserts.assertTrue(cap4.round(2).transformIdle(),
                target + ": stable-delta loop probe is idle");
        Asserts.assertEquals(ShapeSummary.of(cap1.finalAfter()),
                ShapeSummary.of(cap4.finalAfter()),
                target + ": later rounds do not duplicate loop replay");
        PEATestUtils.IRBody stable = cap4.finalAfter();
        stable.assertLineCount("store atomic", 5);
        stable.assertLineCount("load atomic", 5);
        stable.assertLineCount("br i1", 5);
        stable.assertLineCount(MATERIALIZED_PHI, 0);
        stable.assertLineCount(FIELD_PHI, 0);
        stable.assertLineCount(CASE_C_FIELD_PHI, 0);
        stable.assertLineCount(MONITOR_ENTER, 0);
        stable.assertLineCount(MONITOR_EXIT, 0);
    }

    private static void assertRoundCounts(
            PEATestUtils.PEAReport cap1, PEATestUtils.PEAReport cap2,
            PEATestUtils.PEAReport cap4, PEATestUtils.PEAReport cap16,
            Method target, int one, int two, int four, int sixteen) {
        Asserts.assertEquals(cap1.roundCount(), one,
                target + ": exact cap-1 round count");
        Asserts.assertEquals(cap2.roundCount(), two,
                target + ": exact cap-2 round count");
        Asserts.assertEquals(cap4.roundCount(), four,
                target + ": exact cap-4 round count");
        Asserts.assertEquals(cap16.roundCount(), sixteen,
                target + ": exact cap-16 round count");
    }

    private static final class ShapeRun {
        private final Method[] targets;
        private final PEATestUtils.PEAReport[] reports;
        private final ShapeSummary[] summaries;

        ShapeRun(Method[] targets, PEATestUtils.PEAReport[] reports,
                 ShapeSummary[] summaries) {
            this.targets = targets.clone();
            this.reports = reports.clone();
            this.summaries = summaries.clone();
        }

        PEATestUtils.PEAReport report(Method target) {
            return reports[indexOf(target)];
        }

        ShapeSummary summary(Method target) {
            return summaries[indexOf(target)];
        }

        private int indexOf(Method target) {
            int index = Arrays.asList(targets).indexOf(target);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown target " + target);
            }
            return index;
        }
    }

    private record ShapeSummary(
            List<PEATestUtils.AllocationKey> allocations, int stores, int loads,
            int branches, int materializedPhis, int fieldPhis, int caseCPhis,
            int monitorEnters, int monitorExits) {
        static ShapeSummary of(PEATestUtils.IRBody body) {
            return new ShapeSummary(body.allocations().stream()
                    .map(PEATestUtils.AllocationSite::key).toList(),
                    body.lineCount("store atomic"), body.lineCount("load atomic"),
                    body.lineCount("br i1"), body.occurrenceCount(MATERIALIZED_PHI),
                    body.occurrenceCount(FIELD_PHI),
                    body.occurrenceCount(CASE_C_FIELD_PHI),
                    body.occurrenceCount(MONITOR_ENTER),
                    body.occurrenceCount(MONITOR_EXIT));
        }
    }

    public static class TestWrapper {
        public static class Box {
            int value;
            int other;
        }

        private static final String EXPECTED = "18:24:3014:26";
        static Box escaped;
        static int escapeCount;

        public static void main(String[] args) throws Exception {
            new Box();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            reset();
            int folded = replayEnablesFolding(17);
            Asserts.assertEquals(folded, 18, "replay-enabled folding result");
            Asserts.assertNull(escaped, "dead candidate escape is removed");
            Asserts.assertEquals(escapeCount, 0, "dead escape arm is not taken");

            reset();
            int locked = balancedLockReplay(11);
            Asserts.assertEquals(locked, 24, "balanced lock replay result");
            Asserts.assertNotNull(escaped, "locked object escapes");
            Asserts.assertEquals(escaped.value, 11, "locked escaped value");
            Asserts.assertEquals(escaped.other, 12, "post-escape field update");
            Asserts.assertEquals(escapeCount, 1, "one locked escape");

            reset();
            int loop = loopRollback(3, true, 2);
            Asserts.assertEquals(loop, 3014, "loop rollback result");
            Asserts.assertNotNull(escaped, "loop-carried object escapes");
            Asserts.assertEquals(escaped.value, 5, "loop-carried escaped value");
            Asserts.assertEquals(escaped.other, 1, "loop-carried replayed field");
            Asserts.assertEquals(escapeCount, 2, "two loop escape executions");

            int idle = alreadyIdle(9);
            Asserts.assertEquals(idle, 26, "already-idle result");
            String payload = folded + ":" + locked + ":" + loop + ":" + idle;
            Asserts.assertEquals(payload, EXPECTED, "exact outer-iteration payload");
            System.out.println("PEA-RESULT:" + payload);
        }

        public static int replayEnablesFolding(int value) {
            Box guard = new Box();
            guard.value = 0;
            Box candidate = new Box();
            candidate.value = value;
            candidate.other = 1;
            if (guard.value != 0) {
                escape(candidate);
            }
            return candidate.value + candidate.other;
        }

        public static int balancedLockReplay(int value) {
            Box box = new Box();
            box.value = value;
            int observed;
            synchronized (box) {
                escape(box);
                box.other = value + 1;
                observed = box.value + box.other;
            }
            int identity = escaped == box ? 1 : 0;
            return observed + identity;
        }

        public static int alreadyIdle(int value) {
            return value + 17;
        }

        public static int loopRollback(int trips, boolean escapeOnEven, int seed) {
            Box box = new Box();
            box.value = seed;
            box.other = 1;
            int sum = 0;
            for (int i = 0; i < trips; i++) {
                box.value += box.other;
                if (escapeOnEven && (i & 1) == 0) {
                    escape(box);
                }
                sum = sum * 31 + box.value;
            }
            int identity = escapeOnEven && trips > 0 && escaped == box ? 1 : 0;
            return sum + box.other + identity;
        }

        public static void escape(Box box) {
            escaped = box;
            escapeCount++;
        }

        private static void reset() {
            escaped = null;
            escapeCount = 0;
        }
    }
}
