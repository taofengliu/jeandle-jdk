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
 * @summary Exact multi-target PEA harness runner, transcript parser, and dump pairing smoke test
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAHarnessSmoke
 */

package compiler.jeandle.pea;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestPEAHarnessSmoke {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAHarnessSmoke$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method noArgs = TestWrapper.class.getMethod("test");
        Method complex = TestWrapper.class.getMethod("test", int.class, Point.class,
                Point[][].class, int[].class);
        Method decoy = TestWrapper.class.getMethod("testExtra");

        testMethodIds(noArgs, complex, decoy);
        testSyntheticParser(noArgs, complex, decoy);
        testLockReplayParser(noArgs, complex);
        testMalformedTranscripts(noArgs);
        testMalformedLockReplays(noArgs);
        testManagedOptionRejection(noArgs);
        testLockingModes(noArgs);
        testNotCompilableFailsFast();
        testDumpPairing(noArgs, complex);
        testRealShapeRun(noArgs, complex, decoy);
        testIterationsAndExactEffects(noArgs, decoy);
        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, noArgs, complex);

        System.out.println("TestPEAHarnessSmoke: harness OK");
    }

    private static void testMethodIds(Method noArgs, Method complex, Method decoy) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.MethodId extra = PEATestUtils.MethodId.of(decoy);
        String stem = TestWrapper.class.getName().replace('.', '_') + "_test";

        Asserts.assertEquals(first.jvmDescriptor(), "()I");
        Asserts.assertEquals(overloaded.jvmDescriptor(),
                "(ILcompiler/jeandle/pea/TestPEAHarnessSmoke$Point;"
                        + "[[Lcompiler/jeandle/pea/TestPEAHarnessSmoke$Point;[I)I");
        Asserts.assertEquals(first.dumpStem(), stem);
        Asserts.assertEquals(first.llvmFunctionName(), stem + "()I");
        Asserts.assertEquals(overloaded.llvmFunctionName(), stem + overloaded.jvmDescriptor());
        Asserts.assertEquals(first.compileCommandPattern(),
                TestWrapper.class.getName() + "::test()I");
        Asserts.assertFalse(first.isOSR());
        Asserts.assertTrue(PEATestUtils.MethodId.osr(noArgs).isOSR());
        Asserts.assertEquals(PEATestUtils.MethodId.osr(noArgs).llvmFunctionName(),
                "__jeandle_osr." + first.llvmFunctionName());
        Asserts.assertNotEquals(first.llvmFunctionName(), overloaded.llvmFunctionName());
        Asserts.assertNotEquals(first.dumpStem(), extra.dumpStem());
    }

    private static void testSyntheticParser(Method noArgs, Method complex, Method decoy) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.MethodId extra = PEATestUtils.MethodId.of(decoy);

        String transcript = String.join("\r\n",
                before(extra, 0),
                function(extra, "ret i32 99"),
                stats(extra, 9, 9, 9),
                effect("Decoy", extra, "ignored=true"),
                after(extra, 0),
                function(extra, "ret i32 99"),
                before(first, 0),
                function(first, "%alloc = invoke ptr addrspace(1) @jeandle.new_instance()",
                        "%twice = add i32 %x, %x", "ret i32 %twice"),
                stats(first, 1, 0, 0),
                effect("EliminateAllocation", first, "[VO=0]"),
                effect("ReplaceLoad", first, "value=%x"),
                after(first, 0, false),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                before(overloaded, 0),
                function(overloaded, "%alloc = invoke ptr addrspace(1) @jeandle.new_instance()",
                        "ret i32 %arg"),
                stats(overloaded, 1, 0, 0),
                effect("EliminateAllocation", overloaded, "[VO=0]"),
                after(overloaded, 0),
                function(overloaded, "ret i32 %arg"),
                before(first, 1),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                stats(first, 0, 0, 0),
                after(first, 1),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                "");

        PEATestUtils.PEAReport report = PEATestUtils.PEAReport.parse(
                transcript, first, overloaded);
        Asserts.assertEquals(report.report(first).roundCount(), 2);
        Asserts.assertEquals(report.report(overloaded).roundCount(), 1);
        Asserts.assertEquals(report.report(first).round(0).neverEscapes(), 1);
        Asserts.assertEquals(report.report(first).round(1).neverEscapes(), 0);
        Asserts.assertEquals(report.report(first).effects("EliminateAllocation").size(), 1);
        Asserts.assertEquals(report.report(overloaded).effects("EliminateAllocation").size(), 1);

        PEATestUtils.IRBody before = report.report(first).round0Before();
        PEATestUtils.IRBody after = report.report(first).finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1);
        Asserts.assertEquals(before.loweredAllocCount(), 0);
        Asserts.assertEquals(after.peaAllocCount(), 0);
        Asserts.assertEquals(before.lineCount("add i32"), 1);
        Asserts.assertEquals(before.occurrenceCount("%x"), 2);
        before.assertBefore("%alloc", 0, "%twice", 0);
        before.assertBetween("%alloc", 0, "%x", 1, "ret i32", 0);
        before.assertAbsentBetween("%alloc", 0, "does.not.exist", "ret i32", 0);
        after.assertAbsent("jeandle.new_instance");
        Asserts.assertFalse(transcript.contains("function=@" + first.llvmFunctionName()),
                "Descriptor-bearing LLVM operands must exercise quoted parsing");
    }

    private static void testMalformedTranscripts(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        String body = function(id, "ret i32 1");
        String stat = stats(id, 0, 0, 0);

        expectFailure("missing after marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat), id));
        expectFailure("duplicate before marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, before(id, 0), body,
                        after(id, 0), body), id));
        expectFailure("duplicate after marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, after(id, 0), body,
                        after(id, 0), body), id));
        expectFailure("missing transform-idle flag", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat,
                        ";; PEA-DUMP after iter=0 function " + id.llvmFunctionName(), body), id));
        expectFailure("missing stats for active round", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body,
                        after(id, 0, false), body), id));
        PEATestUtils.PEAReport.parse(String.join("\n",
                before(id, 0), body, after(id, 0), body), id)
                .report(id).assertConverged();
        expectFailure("gapped rounds", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, after(id, 0), body,
                        before(id, 2), body, stat, after(id, 2), body), id));
        expectFailure("non-converged report", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat,
                        after(id, 0, false), body), id).report(id).assertConverged());
    }

    private static void testLockReplayParser(Method noArgs, Method complex) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.PEALockReplay firstDepth =
                new PEATestUtils.PEALockReplay(7, 3, 11, 13, 17, 1, 0);
        PEATestUtils.PEALockReplay secondReceiver =
                new PEATestUtils.PEALockReplay(7, 3, 11, 13, 18, 2, 1);
        PEATestUtils.PEALockReplay secondReceiverAlias =
                new PEATestUtils.PEALockReplay(8, 3, 11, 13, 18, 2, 1);
        PEATestUtils.PEALockReplay tenthDepth =
                new PEATestUtils.PEALockReplay(7, 3, 11, 13, 17, 10, 2);
        PEATestUtils.PEALockReplay thirdReceiver =
                new PEATestUtils.PEALockReplay(7, 3, 11, 13, 19, 11, 3);
        PEATestUtils.PEALockReplay thirdReceiverAlias =
                new PEATestUtils.PEALockReplay(8, 3, 11, 13, 19, 11, 3);
        PEATestUtils.PEALockReplay otherSource =
                new PEATestUtils.PEALockReplay(7, 4, 12, 14, 18, 4, 0);
        PEATestUtils.PEALockReplay laterRound =
                new PEATestUtils.PEALockReplay(8, 5, 15, 16, 19, 3, 0);
        PEATestUtils.PEALockReplay otherFunction =
                new PEATestUtils.PEALockReplay(9, 6, 20, 21, 22, 4, 0);

        String transcript = String.join("\n",
                before(first, 0),
                function(first, "ret i32 1"),
                stats(first, 0, 0, 0),
                lockReplay(first, firstDepth),
                lockReplay(first, secondReceiver),
                lockReplay(first, secondReceiverAlias),
                lockReplay(first, tenthDepth),
                lockReplay(first, thirdReceiver),
                lockReplay(first, thirdReceiverAlias),
                lockReplay(first, otherSource),
                effect("ReplaceLoad", first, "depth=10"),
                after(first, 0, false),
                function(first, "ret i32 1"),
                before(overloaded, 0),
                function(overloaded, "ret i32 2"),
                stats(overloaded, 0, 0, 0),
                lockReplay(overloaded, otherFunction),
                after(overloaded, 0),
                function(overloaded, "ret i32 2"),
                before(first, 1),
                function(first, "ret i32 1"),
                stats(first, 0, 0, 0),
                lockReplay(first, laterRound),
                after(first, 1),
                function(first, "ret i32 1"));

        PEATestUtils.PEAReport reports = PEATestUtils.PEAReport.parse(
                transcript, first, overloaded);
        PEATestUtils.PEARound firstRound = reports.report(first).round(0);
        Asserts.assertEquals(firstRound.lockReplays(),
                List.of(firstDepth, secondReceiver, secondReceiverAlias, tenthDepth,
                        thirdReceiver, thirdReceiverAlias, otherSource));
        Asserts.assertEquals(reports.report(first).round(1).lockReplays(),
                List.of(laterRound));
        Asserts.assertEquals(reports.report(overloaded).round(0).lockReplays(),
                List.of(otherFunction));
        Asserts.assertEquals(firstRound.effects().size(), 1,
                "LockReplay diagnostics are typed separately from general effects");

        PEATestUtils.PEALockReplayGroup primary =
                new PEATestUtils.PEALockReplayGroup(7, 3, 11, 13);
        PEATestUtils.PEALockReplayGroup alternate =
                new PEATestUtils.PEALockReplayGroup(7, 4, 12, 14);
        PEATestUtils.PEALockReplayGroup aliasedConsumer =
                new PEATestUtils.PEALockReplayGroup(8, 3, 11, 13);
        Asserts.assertEquals(firstRound.lockReplayGroups().size(), 3);
        Asserts.assertEquals(firstRound.lockReplayGroups().get(primary),
                List.of(firstDepth, secondReceiver, tenthDepth, thirdReceiver));
        Asserts.assertEquals(firstRound.lockReplayGroups().get(aliasedConsumer),
                List.of(secondReceiverAlias, thirdReceiverAlias));
        Asserts.assertEquals(firstRound.lockReplayGroups().get(alternate),
                List.of(otherSource));
        PEATestUtils.PEALockReplayPhysicalGroup physicalPrimary =
                new PEATestUtils.PEALockReplayPhysicalGroup(3, 11, 13);
        PEATestUtils.PEALockReplayPhysicalGroup physicalAlternate =
                new PEATestUtils.PEALockReplayPhysicalGroup(4, 12, 14);
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().size(), 2);
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().get(physicalPrimary),
                List.of(firstDepth, secondReceiver, secondReceiverAlias, tenthDepth,
                        thirdReceiver, thirdReceiverAlias));
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().get(physicalAlternate),
                List.of(otherSource));
        firstRound.assertLockReplaySequence(primary,
                firstDepth, secondReceiver, tenthDepth, thirdReceiver);
        Asserts.assertEquals(firstRound.distinctLockReplaySourceCount(7), 2L);
    }

    private static void testMalformedLockReplays(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        String prefix = "PEA: LockReplay function=@\"" + id.llvmFunctionName() + "\" ";
        String valid = prefix + "logical_escape=1 batch=2 emit_site=3 source=4"
                + " receiver_vo=5 depth=6 ordinal=0";

        expectFailure("LockReplay missing key", () -> parseLockTranscript(id,
                prefix + "logical_escape=1 batch=2 emit_site=3 source=4"
                        + " receiver_vo=5 depth=6"));
        expectFailure("LockReplay extra key", () -> parseLockTranscript(id,
                valid + " extra=7"));
        expectFailure("LockReplay duplicate key", () -> parseLockTranscript(id,
                valid + " depth=7"));
        expectFailure("LockReplay negative value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=-6")));
        expectFailure("LockReplay non-decimal value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=0x6")));
        expectFailure("LockReplay overflow value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=2147483648")));
        expectFailure("LockReplay wrong key", () -> parseLockTranscript(id,
                valid.replace("receiver_vo=5", "receiverVo=5")));
        expectFailure("LockReplay wrong order", () -> parseLockTranscript(id,
                prefix + "batch=2 logical_escape=1 emit_site=3 source=4"
                        + " receiver_vo=5 depth=6 ordinal=0"));
        expectFailure("duplicate LockReplay entry", () -> parseLockTranscript(id,
                valid, valid));
        expectFailure("non-contiguous LockReplay ordinal", () -> parseLockTranscript(id,
                valid, valid.replace("depth=6 ordinal=0", "depth=7 ordinal=2")));
        expectFailure("non-increasing LockReplay depth", () -> parseLockTranscript(id,
                valid, valid.replace("receiver_vo=5", "receiver_vo=6")
                        .replace("ordinal=0", "ordinal=1")));
        expectFailure("conflicting same-ordinal LockReplay alias", () ->
                parseLockTranscript(id, valid,
                        valid.replace("logical_escape=1", "logical_escape=2")
                                .replace("receiver_vo=5", "receiver_vo=6")));
        expectFailure("late same-ordinal LockReplay alias", () ->
                parseLockTranscript(id, valid,
                        valid.replace("receiver_vo=5 depth=6 ordinal=0",
                                "receiver_vo=6 depth=7 ordinal=1"),
                        valid.replace("logical_escape=1", "logical_escape=2")));
        expectFailure("reused LockReplay batch with different identity", () ->
                parseLockTranscript(id, valid,
                        valid.replace("logical_escape=1", "logical_escape=2")
                                .replace("emit_site=3 source=4",
                                        "emit_site=8 source=9")));
    }

    private static void parseLockTranscript(PEATestUtils.MethodId id, String... replays) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add(before(id, 0));
        lines.add(function(id, "ret i32 1"));
        lines.add(stats(id, 0, 0, 0));
        lines.addAll(List.of(replays));
        lines.add(after(id, 0));
        lines.add(function(id, "ret i32 1"));
        PEATestUtils.PEAReport.parse(String.join("\n", lines), id);
    }

    private static void testDumpPairing(Method noArgs, Method complex) throws Exception {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        Path dir = Files.createTempDirectory("pea-harness-dump-parser-");
        try {
            writePair(dir, first.dumpStem(), "100", function(first, "ret i32 11"),
                    function(first, "%alloc = invoke ptr addrspace(1) @new_instance()",
                            "ret i32 12"));
            writePair(dir, overloaded.dumpStem(), "200", function(overloaded, "ret i32 21"),
                    function(overloaded, "ret i32 22"));

            PEATestUtils.IRBody firstFront = PEATestUtils.frontendIR(dir, first);
            PEATestUtils.IRBody firstFinal = PEATestUtils.finalIR(dir, first);
            PEATestUtils.IRBody overloadFront = PEATestUtils.frontendIR(dir, overloaded);
            firstFront.assertPresent("ret i32 11");
            firstFinal.assertPresent("ret i32 12");
            Asserts.assertEquals(firstFinal.loweredAllocCount(), 1,
                    "Optimized dumps use lowered allocation helper names");
            Asserts.assertEquals(firstFinal.peaAllocCount(), 0,
                    "Optimized dumps must not be counted as PEA-stage allocations");
            overloadFront.assertPresent("ret i32 21");

            Files.writeString(dir.resolve(first.dumpStem() + "_orphan.ll"),
                    function(first, "ret i32 31"));
            Files.writeString(dir.resolve(first.dumpStem() + "_different_optimized.ll"),
                    function(first, "ret i32 32"));
            firstFinal.assertPresent("ret i32 12");

            writePair(dir, first.dumpStem(), "300", function(first, "ret i32 41"),
                    function(first, "ret i32 42"));
            expectFailure("ambiguous dump pairs",
                    () -> PEATestUtils.finalIR(dir, first));
        } finally {
            deleteTree(dir);
        }
    }

    private static void testManagedOptionRejection(Method target) {
        expectFailure("HotSpot PEA override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:-JeandleDoPEA"));
        expectFailure("HotSpot PEA assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:JeandleDoPEA=false"));
        expectFailure("LLVM container override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleLLVMOptions=-debug"));
        expectFailure("dump override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDumpDirectory=somewhere"));
        expectFailure("dump assignment override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDumpIR=false"));
        expectFailure("CompileCommand override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:CompileCommand=compileonly,*::*"));
        expectFailure("CompileCommandFile override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:CompileCommandFile=commands.txt"));
        expectFailure("shape compiler-count override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraFlags("-XX:CICompilerCount=2"));
        expectFailure("argument file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("@arguments.txt"));
        expectFailure("HotSpot flags file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:Flags=flags.txt"));
        expectFailure("HotSpot VM options file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:VMOptionsFile=options.txt"));
        expectFailure("iteration override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-pea-iterations=2"));
        expectFailure("trace assignment override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-trace-pea=false"));
        expectFailure("compressed-oops assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:UseCompressedOops=true"));
        expectFailure("compressed-klass assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:UseCompressedClassPointers=true"));
        expectFailure("PEA-off double-dash iteration override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).peaOff()
                        .extraLLVMOptions("--jeandle-pea-iterations=2"));
        String offThenOption = failureMessage("PEA-off then safe LLVM option",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).peaOff()
                        .extraLLVMOptions("-verify-each"));
        String optionThenOff = failureMessage("safe LLVM option then PEA-off",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraLLVMOptions("-verify-each").peaOff());
        Asserts.assertEquals(offThenOption, optionThenOff);
        Asserts.assertEquals(offThenOption,
                "PEA-off runs do not accept extra LLVM options");

        PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDoPEAExtra=false",
                        "-XX:UseCompressedOopsExperimental=true");
        PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-pea-unrelated=1",
                        "-jeandle-trace-pea-extra=false");
    }

    private static void testLockingModes(Method target) {
        PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(1);
        PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(2);
        expectFailure("invalid locking mode zero",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(0));
        expectFailure("invalid locking mode three",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(3));
        expectFailure("repeated same locking mode", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).lockingMode(1).lockingMode(1));
        expectFailure("repeated different locking mode", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).lockingMode(1).lockingMode(2));
        expectFailure("raw experimental unlock flag", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).extraFlags("-XX:+UnlockExperimentalVMOptions"));
        expectFailure("raw locking mode flag", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).extraFlags("-XX:LockingMode=2"));
    }

    private static void testRealShapeRun(Method noArgs, Method complex, Method decoy)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, noArgs, complex)
                .lockingMode(1)
                .run()) {
            List<String> command = run.command();
            assertLockingModeCommand(command, 1);
            Asserts.assertEquals(command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions=")).count(), 1L);
            String llvm = command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions="))
                    .findFirst().orElseThrow();
            List<String> actualLLVMOptions = List.of(llvm.substring(
                    "-XX:JeandleLLVMOptions=".length()).split(" "));
            List<String> expectedLLVMOptions = List.of(
                    "-jeandle-trace-pea",
                    "-jeandle-dump-pea-stats",
                    "-jeandle-pea-analyze-function="
                            + PEATestUtils.MethodId.of(noArgs).llvmFunctionName(),
                    "-jeandle-dump-pea-ir-function="
                            + PEATestUtils.MethodId.of(noArgs).llvmFunctionName(),
                    "-jeandle-pea-analyze-function="
                            + PEATestUtils.MethodId.of(complex).llvmFunctionName(),
                    "-jeandle-dump-pea-ir-function="
                            + PEATestUtils.MethodId.of(complex).llvmFunctionName());
            Asserts.assertEquals(actualLLVMOptions.stream().sorted().toList(),
                    expectedLLVMOptions.stream().sorted().toList());
            for (Method method : List.of(noArgs, complex)) {
                String function = PEATestUtils.MethodId.of(method).llvmFunctionName();
                Asserts.assertTrue(llvm.contains("-jeandle-pea-analyze-function=" + function));
                Asserts.assertTrue(llvm.contains("-jeandle-dump-pea-ir-function=" + function));
                PEATestUtils.PEAReport report = run.report(method);
                Asserts.assertTrue(report.round(0).hasStats());
                Asserts.assertEquals(report.round(0).neverEscapes(), 1);
                Asserts.assertEquals(report.round(0).partiallyEscapes(), 0);
                Asserts.assertEquals(report.round(0).alwaysEscapes(), 0);
                Asserts.assertEquals(report.effects("EliminateAllocation").size(), 1);
                Asserts.assertEquals(report.effects("ReplaceLoad").size(), 1);
                Asserts.assertEquals(report.round0Before().peaAllocCount(), 1);
                Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0);
                Asserts.assertEquals(run.frontendIR(method).peaAllocCount(), 1);
                Asserts.assertEquals(run.finalIR(method).loweredAllocCount(), 0);
                run.finalIR(method).assertAbsent("@new_instance(");
            }
            Asserts.assertTrue(command.contains("-XX:CICompilerCount=1"));
            Asserts.assertTrue(command.contains("-XX:-UseCompressedOops"));
            Asserts.assertTrue(command.contains("-XX:-UseCompressedClassPointers"));
            Asserts.assertFalse(run.output().getStderr().contains(
                    PEATestUtils.MethodId.of(decoy).llvmFunctionName()));
        }
    }

    private static void testIterationsAndExactEffects(Method target, Method helper)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .peaIterations(4)
                .run()) {
            List<String> command = run.command();
            Asserts.assertFalse(command.contains("-XX:+UnlockExperimentalVMOptions"));
            Asserts.assertFalse(command.stream()
                    .anyMatch(s -> s.startsWith("-XX:LockingMode=")));
            String llvm = command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions="))
                    .findFirst().orElseThrow();
            Asserts.assertEquals(List.of(llvm.substring(
                    "-XX:JeandleLLVMOptions=".length()).split(" ")).stream()
                    .filter("-jeandle-pea-iterations=4"::equals).count(), 1L);
            PEATestUtils.PEAReport report = run.report(target);
            Asserts.assertTrue(report.roundCount() >= 2, "configured outer rounds");
            Asserts.assertTrue(report.round(report.roundCount() - 1).transformIdle(),
                    "last observed round must be transform-idle");
            report.round(0).uniqueEffect("EliminateAllocation", "jeandle.new_instance");
            Asserts.assertEquals(report.round(0).effectCount(
                    "EliminateAllocation", "jeandle.new_instance"), 1L);
            Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 1);
            PEATestUtils.IRBlock allocationBlock = report.round0Before()
                    .blockContaining("@jeandle.new_instance", 0);
            Asserts.assertEquals(allocationBlock.occurrenceCount(
                    "@jeandle.new_instance"), 1);
            allocationBlock.assertAbsent("@jeandle.new_array");
            allocationBlock.assertBefore("@jeandle.new_instance", 0, "to label", 0);
        }

        Asserts.assertThrows(IllegalArgumentException.class,
                () -> PEATestUtils.shapeRun(WRAPPER, target).peaIterations(0));
        Asserts.assertThrows(IllegalArgumentException.class,
                () -> PEATestUtils.shapeRun(WRAPPER, target).peaIterations(17));

        PEATestUtils.PEAOnOffResult comparison = PEATestUtils.behaviorRun(WRAPPER, target)
                .lockingMode(2)
                .peaIterations(4)
                .dontinline(helper)
                .runPEAOnOffEquivalentWithCommands();
        assertLockingModeCommand(comparison.onCommand(), 2);
        assertLockingModeCommand(comparison.offCommand(), 2);
        Asserts.assertThrows(UnsupportedOperationException.class,
                () -> comparison.onCommand().add("-version"));
    }

    private static void assertLockingModeCommand(List<String> command, int mode) {
        String unlock = "-XX:+UnlockExperimentalVMOptions";
        String lockingMode = "-XX:LockingMode=" + mode;
        Asserts.assertEquals(command.stream().filter(unlock::equals).count(), 1L);
        Asserts.assertEquals(command.stream().filter(lockingMode::equals).count(), 1L);
        Asserts.assertTrue(command.indexOf(unlock) < command.indexOf(lockingMode),
                "Experimental options must be unlocked before selecting LockingMode");
    }

    private static void testNotCompilableFailsFast() throws Exception {
        ProcessBuilder process = ProcessTools.createLimitedTestJavaProcessBuilder(
                "-Xbootclasspath/a:.",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                CompileFailureWrapper.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(process);
        output.shouldHaveExitValue(0);
        output.shouldContain("PEATestUtils not-compilable fail-fast: OK");
    }

    private static String before(PEATestUtils.MethodId id, int round) {
        return ";; PEA-DUMP before iter=" + round + " function " + id.llvmFunctionName();
    }

    private static String after(PEATestUtils.MethodId id, int round) {
        return after(id, round, true);
    }

    private static String after(PEATestUtils.MethodId id, int round,
                                boolean transformIdle) {
        return ";; PEA-DUMP after iter=" + round + " function " + id.llvmFunctionName()
                + " transform_idle=" + transformIdle;
    }

    private static String stats(PEATestUtils.MethodId id, int never, int partial, int always) {
        return ";; PEA stats @" + id.llvmFunctionName() + ": NeverEscapes=" + never
                + " PartiallyEscapes=" + partial + " AlwaysEscapes=" + always;
    }

    private static String effect(String kind, PEATestUtils.MethodId id, String detail) {
        return "PEA: " + kind + " function=@\"" + id.llvmFunctionName() + "\" " + detail;
    }

    private static String lockReplay(PEATestUtils.MethodId id,
                                     PEATestUtils.PEALockReplay replay) {
        return "PEA: LockReplay function=@\"" + id.llvmFunctionName() + "\""
                + " logical_escape=" + replay.logicalEscape()
                + " batch=" + replay.batch()
                + " emit_site=" + replay.emitSite()
                + " source=" + replay.source()
                + " receiver_vo=" + replay.receiverVO()
                + " depth=" + replay.depth()
                + " ordinal=" + replay.ordinal();
    }

    private static String function(PEATestUtils.MethodId id, String... instructions) {
        return "define hotspotcc i32 @\"" + id.llvmFunctionName() + "\"() {\nentry:\n  "
                + String.join("\n  ", instructions) + "\n}";
    }

    private static void writePair(Path dir, String stem, String timestamp,
                                  String frontend, String optimized) throws IOException {
        Files.writeString(dir.resolve(stem + "_" + timestamp + ".ll"), frontend);
        Files.writeString(dir.resolve(stem + "_" + timestamp + "_optimized.ll"), optimized);
    }

    private static void expectFailure(String label, ThrowingRunnable action) {
        System.out.println("expected parser rejection: " + label + ": "
                + failureMessage(label, action));
    }

    private static String failureMessage(String label, ThrowingRunnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return expected.getMessage();
        } catch (Exception unexpected) {
            throw new RuntimeException("Wrong exception for " + label, unexpected);
        }
        throw new RuntimeException("Expected failure: " + label);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static class Point {
        int x;
    }

    public static class TestWrapper {
        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int first = test();
            int second = test(5, new Point(), new Point[1][1], new int[]{7});
            System.out.println("PEA-RESULT:" + first + "," + second);
        }

        public static int test() {
            Point point = new Point();
            point.x = 7;
            return point.x;
        }

        public static int test(int seed, Point unused, Point[][] nested, int[] values) {
            Point point = new Point();
            point.x = seed + nested.length + values[0];
            return point.x;
        }

        public static int testExtra() {
            return 99;
        }
    }

    public static class CompileFailureWrapper {
        public static void main(String[] args) throws Exception {
            Method method = CompileFailureWrapper.class.getMethod("target");
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            whiteBox.makeMethodNotCompilable(method, 4);

            long start = System.nanoTime();
            RuntimeException failure = Asserts.assertThrows(RuntimeException.class,
                    () -> PEATestUtils.enqueueAndAwaitLevel4(method));
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
            Asserts.assertTrue(failure.getMessage().contains("not compilable at level 4"),
                    "fail-fast reports the compilation state: " + failure.getMessage());
            Asserts.assertTrue(elapsedMillis < 10_000,
                    "not-compilable target must fail promptly, elapsed=" + elapsedMillis + "ms");
            System.out.println("PEATestUtils not-compilable fail-fast: OK");
        }

        public static int target() {
            return 1;
        }
    }
}
