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
        testMalformedTranscripts(noArgs);
        testManagedOptionRejection(noArgs);
        testDumpPairing(noArgs, complex);
        testRealShapeRun(noArgs, complex, decoy);
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
                after(first, 0),
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
        expectFailure("gapped rounds", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, after(id, 0), body,
                        before(id, 2), body, stat, after(id, 2), body), id));
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

    private static void testRealShapeRun(Method noArgs, Method complex, Method decoy)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, noArgs, complex).run()) {
            List<String> command = run.command();
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

    private static String before(PEATestUtils.MethodId id, int round) {
        return ";; PEA-DUMP before iter=" + round + " function " + id.llvmFunctionName();
    }

    private static String after(PEATestUtils.MethodId id, int round) {
        return ";; PEA-DUMP after iter=" + round + " function " + id.llvmFunctionName()
                + " transform_idle=false";
    }

    private static String stats(PEATestUtils.MethodId id, int never, int partial, int always) {
        return ";; PEA stats @" + id.llvmFunctionName() + ": NeverEscapes=" + never
                + " PartiallyEscapes=" + partial + " AlwaysEscapes=" + always;
    }

    private static String effect(String kind, PEATestUtils.MethodId id, String detail) {
        return "PEA: " + kind + " function=@\"" + id.llvmFunctionName() + "\" " + detail;
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
}
