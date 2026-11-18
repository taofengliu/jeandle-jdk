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
 * @summary Verify bounded poll batches on optimized current, widened and irregular loops
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.utils.IRDumpParser
 * @run driver TestSafepointBudgetLoops
 */

import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestSafepointBudgetLoops {
    private static volatile int sink;
    private static volatile Object live;
    private static final String[] METHODS = {
        "current", "widened", "irregular", "swapping", "covered", "split"
    };

    static int current(int n) {
        int i = 0;
        int sum = 0;
        do {
            sum += i;
            sink = i;
        } while (i++ < n);
        return sum ^ i;
    }

    static int widened(int start, long limit) {
        int sum = 0;
        for (int i = start; (long) i < limit; i++) {
            sink = i;
            sum += i;
        }
        return sum;
    }

    static int irregular(int[] steps, int n, int stop) {
        int sum = 0;
        for (int i = 0; i < n; i += steps[i & 3]) {
            if (i == stop) break;
            sum += i;
            sink = i;
        }
        return sum;
    }

    static int swapping(int[] steps, int n, Object a, Object b) {
        int sum = 0;
        for (int i = 0; i < n; i += steps[i & 3]) {
            Object tmp = a;
            a = b;
            b = tmp;
            live = a;
            sum += i;
            sink = i;
        }
        return sum + (a == b ? 1 : 0);
    }

    static int split(int[] steps, int n, int stop) {
        int sum = 0;
        int i = 0;
        while (i < n) {
            if (i == stop) break;
            if ((i & 1) == 0) {
                sum += i;
                sink = sum;
                i += steps[i & 3];
                continue;
            }
            sum -= i;
            sink = sum;
            i += steps[i & 3];
        }
        return sum ^ i;
    }

    static int callA(int i) { sink = i; return i + 1; }
    static int callB(int i) { sink = i; return i - 1; }

    static int covered(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if ((sink & 1) == 0) sum += callA(i);
            else sum += callB(i);
        }
        return sum;
    }

    private static void child() {
        for (int n : new int[] {-1, 0, 1, 3, 4, 5, 31, 32, 33, 73}) {
            int count = Math.max(1, n + 1);
            int expected = (count * (count - 1) / 2) ^ count;
            Asserts.assertEquals(current(n), expected, "current state");
            Asserts.assertEquals(widened(0, Math.max(0, n)),
                                 Math.max(0, n) * Math.max(0, n - 1) / 2,
                                 "widened test");
            int[] steps = {1, 3, 1, 3};
            for (int stop : new int[] {-1, 7, 31}) {
                int sum = 0;
                for (int j = 0; j < n && j != stop; j += steps[j & 3]) sum += j;
                Asserts.assertEquals(irregular(steps, n, stop), sum, "side exit");
                int splitSum = 0;
                int j = 0;
                for (; j < n && j != stop; j += steps[j & 3]) {
                    splitSum += (j & 1) == 0 ? j : -j;
                }
                Asserts.assertEquals(split(steps, n, stop), splitSum ^ j,
                                     "split backedge state");
            }
            int sum = 0;
            for (int j = 0; j < n; j += steps[j & 3]) sum += j;
            Object a = new Object();
            Object b = new Object();
            Asserts.assertEquals(swapping(steps, n, a, b), sum, "cyclic oops");
            int expectedCalls = 0;
            int state = 0;
            for (int j = 0; j < n; j++) {
                expectedCalls += (state & 1) == 0 ? j + 1 : j - 1;
                state = j;
            }
            sink = 0;
            Asserts.assertEquals(covered(n), expectedCalls, "branch calls");
        }
        Asserts.assertEquals(widened(Integer.MAX_VALUE - 3, Integer.MAX_VALUE),
                             (Integer.MAX_VALUE - 3) + (Integer.MAX_VALUE - 2)
                             + (Integer.MAX_VALUE - 1), "near root overflow");
        System.out.println("SEMANTICS_OK");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            child();
            return;
        }
        for (boolean pea : new boolean[] {false, true}) {
            for (int budget : new int[] {0, 1, 2, 4, 32}) {
                List<String> cmd = new ArrayList<>(List.of(
                    "-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                    "-XX:" + (pea ? "+" : "-") + "JeandleDoPEA",
                    "-XX:JeandleLoopStripMiningIter=" + budget,
                    "-XX:CompileCommand=dontinline,TestSafepointBudgetLoops::callA",
                    "-XX:CompileCommand=dontinline,TestSafepointBudgetLoops::callB",
                    "-XX:JeandleLLVMOptions=--print-after=safepoint-budget-strip-mining "
                        + "--print-after=safepoint-poll-elimination"));
                for (String method : METHODS) {
                    cmd.add("-XX:CompileCommand=compileonly,TestSafepointBudgetLoops::" + method);
                }
                cmd.add("TestSafepointBudgetLoops");
                cmd.add("child");
                OutputAnalyzer out = ProcessTools.executeProcess(
                    ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
                out.shouldHaveExitValue(0).shouldContain("SEMANTICS_OK");
                for (String method : METHODS) {
                    String suffix = "TestSafepointBudgetLoops_" + method;
                    String early = IRDumpParser.extractNthSection(
                        out.getOutput(), "After", "SafepointPollElimination", suffix, 0);
                    Asserts.assertFalse(early.isEmpty(), method + ": target not compiled");
                    if (method.equals("covered")) {
                        Asserts.assertEquals(IRDumpParser.countPolls(IRDumpParser.extractNthSection(
                            out.getOutput(), "After", "SafepointPollElimination",
                            suffix, budget == 0 ? 0 : 1)), 1,
                                             "branch calls cover the loop");
                        continue;
                    }
                    if (budget > 1) {
                        String batch = IRDumpParser.extractNthSection(
                            out.getOutput(), "After", "SafepointBudgetStripMining", suffix, 0);
                        Asserts.assertFalse(batch.isEmpty(), method + ": missing fallback pass");
                        Asserts.assertTrue(batch.contains(".batch.poll") || batch.contains(".outer.latch")
                            || (batch.contains("batch.outer:") && batch.contains(".fast:")),
                                           method + ": expected a bounded batch, PEA=" + pea);
                        if (method.equals("split")) {
                            Asserts.assertTrue(batch.contains("batch.outer:")
                                    && batch.contains(".fast:"),
                                    "split backedges must reach clone fallback, PEA=" + pea);
                        }
                    } else {
                        Asserts.assertFalse(early.contains(".batch.poll"), "disabled fallback");
                    }
                }
                System.out.println("VERIFIED PEA=" + pea + " budget=" + budget);
            }
        }
    }
}
