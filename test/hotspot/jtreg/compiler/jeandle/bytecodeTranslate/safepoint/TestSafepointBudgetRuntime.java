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
 * @summary Verify wrapping-root poll batches preserve oop and current state across GC and deopt
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.utils.IRDumpParser
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver/timeout=240 compiler.jeandle.bytecodeTranslate.safepoint.TestSafepointBudgetRuntime
 */

package compiler.jeandle.bytecodeTranslate.safepoint;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import compiler.jeandle.utils.IRDumpParser;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestSafepointBudgetRuntime {
    private static WhiteBox WB;
    private static final int START = Integer.MAX_VALUE - 1024;
    private static volatile boolean stop;
    private static volatile long progress;
    private static volatile Object observed;
    private static volatile Throwable failure;
    private static long result;
    private static int finalIV;
    private static Object finalA;
    private static Object finalB;

    public static void worker(Object a, Object b, long limit) {
        int i = START;
        long sum = 0;
        long iterations = 0;
        while ((long) i < limit) {
            if (stop) break;
            Object tmp = a;
            a = b;
            b = tmp;
            sum += i;
            i++;
            iterations++;
            observed = a;
            progress = iterations;
        }
        result = sum;
        finalIV = i;
        finalA = a;
        finalB = b;
    }

    public static void splitWorker(Object a, Object b, long limit) {
        int i = START;
        long sum = 0;
        long iterations = 0;
        while ((long) i < limit) {
            if (stop) break;
            Object tmp = a;
            a = b;
            b = tmp;
            sum += i;
            i++;
            iterations++;
            observed = a;
            progress = iterations;
            if ((i & 1) == 0) continue;
            // Distinct backward bytecodes retain their own deopt BCIs.
            if (stop) break;
        }
        result = sum;
        finalIV = i;
        finalA = a;
        finalB = b;
    }

    private static void awaitProgress(long value) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (progress <= value && System.nanoTime() < deadline) Thread.sleep(1);
        if (progress <= value) throw new RuntimeException("worker stopped making progress");
    }

    private static long collections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (String mode : new String[] {"normal", "osr", "clone-normal", "clone-osr"}) {
                boolean cloned = mode.startsWith("clone-");
                String method = cloned ? "splitWorker" : "worker";
                Path log = Path.of("budget-runtime-" + mode + ".xml").toAbsolutePath();
                List<String> cmd = new ArrayList<>(List.of(
                    "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=100",
                    "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI", "-Xbootclasspath/a:.",
                    "-XX:+UseJeandleCompiler", "-XX:+UseSerialGC", "-Xmx256m",
                    "-XX:+LogCompilation", "-XX:LogFile=" + log,
                    "-XX:JeandleLoopStripMiningIter=32",
                    "-XX:JeandleLLVMOptions=--print-after=safepoint-budget-strip-mining",
                    "-XX:CompileCommand=compileonly," + TestSafepointBudgetRuntime.class.getName()
                        + "::" + method, TestSafepointBudgetRuntime.class.getName(), mode));
                OutputAnalyzer out = ProcessTools.executeProcess(
                    ProcessTools.createLimitedTestJavaProcessBuilder(cmd));
                out.shouldHaveExitValue(0).shouldContain("STATE_OK");
                String ir = IRDumpParser.extractNthSection(out.getOutput(), "After",
                    "SafepointBudgetStripMining", "TestSafepointBudgetRuntime_" + method, 0);
                Asserts.assertTrue(ir.contains("batch.tick.next"),
                                   "worker did not use bounded batches: " + mode);
                Asserts.assertTrue(cloned
                    ? ir.contains("batch.outer:") && ir.contains(".fast:")
                    : ir.contains(".batch.check:") && ir.contains(".batch.poll:"),
                    "worker did not use the expected batch strategy: " + mode);
                String output = out.getOutput();
                int begin = output.indexOf("ACTIVE_BEFORE_GC");
                int end = output.indexOf("ACTIVE_AFTER_GC");
                int deopt = output.indexOf("DEOPT completed");
                Asserts.assertTrue(begin >= 0 && end > begin && deopt > end,
                                   "missing active worker event interval");
                Matcher id = Pattern.compile("ACTIVE_NMETHOD id=(\\d+)").matcher(output);
                Asserts.assertTrue(id.find(), "missing nmethod identity");
                String compileId = id.group(1);
                String xml = Files.readString(log);
                Matcher active = Pattern.compile("(?s)<deoptimized [^>]*compile_id='"
                    + compileId + "'[^>]*>.*?</deoptimized>").matcher(xml);
                Asserts.assertTrue(active.find(), "no actual active-frame deoptimization");
                String event = active.group();
                Asserts.assertTrue(event.contains("TestSafepointBudgetRuntime " + method)
                    && event.contains("compiler='jeandle'"),
                                   "deoptimized frame does not belong to worker");
                Asserts.assertEquals(event.contains("compile_kind='osr'"), mode.endsWith("osr"),
                                     "incorrect active frame compilation mode");
                System.out.println(output.substring(begin));
                System.out.println("ACTIVE_DEOPT_EVENT " + event.replace('\n', ' '));
            }
            return;
        }
        WB = WhiteBox.getWhiteBox();
        boolean osr = args[0].endsWith("osr");
        boolean cloned = args[0].startsWith("clone-");
        Method target = TestSafepointBudgetRuntime.class.getDeclaredMethod(
                cloned ? "splitWorker" : "worker", Object.class, Object.class, long.class);
        if (!osr) {
            if (!WB.enqueueMethodForCompilation(target, 4))
                throw new RuntimeException("compilation was not queued");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!WB.isMethodCompiled(target) && System.nanoTime() < deadline) Thread.sleep(1);
            if (!WB.isMethodCompiled(target)) throw new RuntimeException("worker was not compiled");
        }

        Object first = new byte[1024];
        Object second = new byte[2048];
        Thread thread = new Thread(() -> {
            try {
                if (cloned) splitWorker(first, second, Long.MAX_VALUE);
                else worker(first, second, Long.MAX_VALUE);
            }
            catch (Throwable t) { failure = t; }
        });
        thread.setDaemon(true);
        thread.start();
        // This is beyond the first signed i32 wrap.
        awaitProgress(20_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!WB.isMethodCompiled(target, osr) && System.nanoTime() < deadline) Thread.sleep(1);
        if (!WB.isMethodCompiled(target, osr)) throw new RuntimeException("missing target nmethod");
        System.out.println("COMPILED worker osr=" + osr + " level="
                           + WB.getMethodCompilationLevel(target, osr));
        System.out.println("ACTIVE_BEFORE_GC");
        if (WB.handshakeWalkStack(thread, false) != 1)
            throw new RuntimeException("worker stack was not sampled");
        int compileId = (Integer) WB.getNMethod(target, osr)[3];
        System.out.println("ACTIVE_NMETHOD id=" + compileId);
        awaitProgress(progress + 20_000);
        long beforeGC = progress;
        long beforeCollections = collections();
        WB.youngGC();
        WB.fullGC();
        awaitProgress(beforeGC);
        if (collections() <= beforeCollections) throw new RuntimeException("no GC occurred");
        Object liveOop = observed;
        if (liveOop != first && liveOop != second) throw new RuntimeException("lost live oop");
        System.out.println("GC completed while worker active; progress=" + progress
                           + " collectionsBefore=" + beforeCollections
                           + " collectionsAfter=" + collections());

        System.out.println("ACTIVE_AFTER_GC");
        if (WB.handshakeWalkStack(thread, false) != 1)
            throw new RuntimeException("worker stack was not sampled after GC");
        if ((Integer) WB.getNMethod(target, osr)[3] != compileId)
            throw new RuntimeException("worker nmethod changed during GC");
        long beforeDeopt = progress;
        WB.deoptimizeMethod(target, osr);
        if (WB.isMethodCompiled(target, osr)) throw new RuntimeException("worker was not deoptimized");
        awaitProgress(beforeDeopt);
        System.out.println("DEOPT completed; progress=" + progress);
        stop = true;
        thread.join(TimeUnit.SECONDS.toMillis(30));
        if (thread.isAlive()) throw new RuntimeException("worker did not stop");
        if (failure != null) throw new RuntimeException("worker failed", failure);
        long count = progress;
        int expectedIV = START;
        long expected = 0;
        for (long n = 0; n < count; n++) {
            expected += expectedIV;
            expectedIV++;
        }
        if (result != expected || finalIV != expectedIV)
            throw new RuntimeException("incorrect current/next/reduction after deopt");
        if (finalA != ((count & 1) == 0 ? first : second)
                || finalB != ((count & 1) == 0 ? second : first))
            throw new RuntimeException("incorrect cyclic oop recurrence after deopt");
        System.out.println("STATE_OK iterations=" + count + " finalIV=" + finalIV);
    }
}
