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
 * @summary Coalesce polls across inlined branches and fully unrolled loops
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.utils.IRDumpParser
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestSafepointPollCoalescing
 */

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestSafepointPollCoalescing {
    static int adjust(int value) {
        int i = 0;
        while (i < 3) {
            if (value < 0) {
                value = (value * 31) ^ 0x1234;
                i++;
                continue;
            }
            value = (value * 17) ^ 0x4567;
            i++;
        }
        return value;
    }

    static int diamonds(int seed) {
        int value = adjust(seed);
        value = adjust(value + 1);
        return adjust(value ^ 0x76543210);
    }

    static int unrolled(int seed) {
        int value = seed;
        for (int i = 0; i < 4; i++) {
            value = adjust(value + i);
        }
        return value;
    }

    private static int reference(int value) {
        for (int i = 0; i < 3; i++) {
            value = value < 0 ? (value * 31) ^ 0x1234 : (value * 17) ^ 0x4567;
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            WhiteBox wb = WhiteBox.getWhiteBox();
            for (String name : List.of("diamonds", "unrolled")) {
                Method method = TestSafepointPollCoalescing.class.getDeclaredMethod(name, int.class);
                Asserts.assertTrue(wb.enqueueMethodForCompilation(method, 4), name + ": enqueue");
                Asserts.assertTrue(wb.isMethodCompiled(method), name + ": ordinary entry missing");
                Asserts.assertEquals(wb.getMethodCompilationLevel(method), 4, name + ": level");
            }
            for (int seed = -256; seed < 256; seed++) {
                int expected = reference(reference(reference(seed) + 1) ^ 0x76543210);
                Asserts.assertEquals(diamonds(seed), expected, "diamonds");
                expected = seed;
                for (int i = 0; i < 4; i++) expected = reference(expected + i);
                Asserts.assertEquals(unrolled(seed), expected, "unrolled");
            }
            System.out.println("COMPILED_SEMANTICS_OK");
            return;
        }
        for (boolean branchCuts : new boolean[] {false, true}) {
            for (int budget : new int[] {0, 32}) {
                Path log = Path.of("coalescing-" + budget + "-" + branchCuts + ".xml").toAbsolutePath();
                List<String> cmd = new ArrayList<>(List.of(
                    "-Xbatch", "-XX:-TieredCompilation", "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+UseJeandleCompiler", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:JeandleLoopStripMiningIter=" + budget,
                    "-XX:CompileCommand=inline,TestSafepointPollCoalescing::adjust",
                    "-XX:CompileCommand=compileonly,TestSafepointPollCoalescing::diamonds",
                    "-XX:CompileCommand=compileonly,TestSafepointPollCoalescing::unrolled",
                    "-XX:JeandleLLVMOptions=--print-before=safepoint-poll-elimination "
                        + "--print-after=safepoint-poll-elimination "
                        + "--jeandle-enable-branch-poll-coalescing=" + branchCuts,
                    "TestSafepointPollCoalescing", "child"));
                OutputAnalyzer out = ProcessTools.executeProcess(
                    ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
                out.shouldHaveExitValue(0).shouldContain("COMPILED_SEMANTICS_OK");
                String xml = Files.readString(log);
                for (String method : List.of("diamonds", "unrolled")) {
                    String suffix = "TestSafepointPollCoalescing_" + method;
                    String before = IRDumpParser.extractNthSection(
                        out.getOutput(), "Before", "SafepointPollElimination", suffix, 0);
                    String after = IRDumpParser.extractNthSection(
                        out.getOutput(), "After", "SafepointPollElimination", suffix, 0);
                    System.out.println("INPUT " + method + "\n" + before);
                    System.out.println("OUTPUT " + method + "\n" + after);
                    Asserts.assertTrue(IRDumpParser.countPolls(before) >= 3,
                        method + ": target did not reach pass with redundant polls");
                    if (branchCuts) {
                        Asserts.assertEquals(IRDumpParser.countPolls(after), 1,
                            method + ": must retain only the final return cut");
                    } else {
                        Asserts.assertTrue(IRDumpParser.countPolls(after) >= 3,
                            method + ": branch-local polls must remain by default");
                        Asserts.assertTrue(IRDumpParser.countPolls(after) <= IRDumpParser.countPolls(before),
                            method + ": coalescing must not insert polls");
                    }
                    Asserts.assertTrue(xml.lines().anyMatch(line -> line.contains("<nmethod ")
                        && line.contains("compiler='jeandle'")
                        && line.contains("method='TestSafepointPollCoalescing " + method + " ")
                        && !line.contains("compile_kind='osr'")), method + ": Jeandle nmethod missing");
                    System.out.println("VERIFIED " + method + " budget=" + budget + " branchCuts=" + branchCuts);
                }
            }
        }
    }
}
