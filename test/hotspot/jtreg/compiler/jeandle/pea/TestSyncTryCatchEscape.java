/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
 * License version 2 for more details (a copy is included in the LICENSE
 * file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA lock re-emit balance: a locked VO that
 *          escapes on a subset of paths (a conditional escape inside
 *          synchronized, plus an exceptional path) must have its elided
 *          monitorenter re-emitted EXACTLY ONCE per dynamic path, with the
 *          matching monitorexit surviving on every path (no double acquire,
 *          no lock leak). Pre-fix, per-pred materialization re-emitted the
 *          lock at a shared predecessor's terminator without flipping the
 *          sibling state, so sibling paths double-acquired or leaked.
 *          Throws IllegalMonitorStateException / hangs when broken.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestSyncTryCatchEscape
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestSyncTryCatchEscape {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestSyncTryCatchEscape$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::testEscape",
                "-XX:CompileCommand=compileonly," + wrapper + "::testThrow",
                "-XX:CompileCommand=dontinline," + wrapper + "::sink",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // IR check (testEscape): o is PartiallyEscapes (escapes via sink(o)
        // on the escape==true branch), so its allocation is RETAINED; the
        // elided monitorenter is re-emitted (a lowered enter expansion is
        // present) and the exits survive as real exits (normal + exceptional
        // cleanup paths of the synchronized region). Exact once-per-path
        // balance is asserted behaviorally below.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("testEscape", boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*testEscape.*");
        checker.check("new_instance");
        checker.checkPattern("monitorenter_with_.*_lock.exit:");
        checker.checkPattern("monitorexit_with_.*_lock.exit:");

        // Behavioral: many iterations over every path combination. A double
        // acquire or a leaked lock surfaces as IllegalMonitorStateException
        // at return/unlock (or a hang).
        output.shouldContain("TestSyncTryCatchEscape escape: OK");
        output.shouldContain("TestSyncTryCatchEscape throw: OK");
        output.shouldNotContain("IllegalMonitorStateException");
    }

    public static class TestWrapper {
        public static class Point { public int x = 1; public int y = 2; }

        static Point global; // opaque escape target
        static void sink(Point p) { global = p; }

        public static void main(String[] args) {
            new Point(); // init class
            int sum = 0;
            for (int i = 0; i < 2000; i++) {
                sum += testEscape(i % 2 == 0);
            }
            System.out.println("TestSyncTryCatchEscape escape: OK (" + sum + ")");
            int tsum = 0;
            for (int i = 0; i < 2000; i++) {
                tsum += testThrow(i % 2 == 0);
            }
            System.out.println("TestSyncTryCatchEscape throw: OK (" + tsum + ")");
            Asserts.assertEquals(sum, 2000 * 3);
            Asserts.assertEquals(tsum, 2000 * 3);
        }

        // o is virtual; its lock is elided. On the escape branch o
        // materializes (sink) and the lock is re-emitted once; on the other
        // branch o never escapes. Every path releases exactly what it
        // acquired.
        public static int testEscape(boolean escape) {
            Point o = new Point();
            synchronized (o) {
                if (escape) sink(o);
            }
            return o.x + o.y;
        }

        // synchronized(o){ try{ foo(o) }catch(T){ bar(o) } } — the review
        // §3 #5 shape: foo(o) escapes (and may throw); the handler also
        // escapes. One acquire per path, no leak on the unwind edge.
        public static int testThrow(boolean doThrow) {
            Point o = new Point();
            int r = 0;
            synchronized (o) {
                try {
                    r = mayThrow(o, doThrow);
                } catch (MarkerException e) {
                    sink(o);
                    r = o.x;
                }
            }
            return r + o.y;
        }

        static class MarkerException extends Exception { }

        static int mayThrow(Point o, boolean doThrow) throws MarkerException {
            sink(o);                      // o escapes at a real call site
            if (doThrow) throw new MarkerException();
            return o.x;
        }
    }
}
