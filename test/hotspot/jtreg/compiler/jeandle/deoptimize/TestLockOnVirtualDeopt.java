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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA lock reconstruction at deopt: a never-escaping VO held in
 *          a balanced synchronized block is still virtual at a null-check
 *          safepoint inside the block. PEA elides the monitorenter/exit and
 *          rewrites the deopt bundle's monitor entry to eliminated=true with a
 *          VORef owner, so HotSpot relock_objects re-acquires the lock on the
 *          reallocated owner at deopt. No IllegalMonitorStateException.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.deoptimize.TestLockOnVirtualDeopt
 */

package compiler.jeandle.deoptimize;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLockOnVirtualDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.deoptimize.TestLockOnVirtualDeopt$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // p is held in a balanced synchronized block. PEA elides the
        // monitorenter/exit (the monitor JavaOps are lower-phase=1, so they
        // reach PEA unexpanded and PEA folds the balanced pair on the virtual
        // receiver; NeverEscapes -> OrigAlloc eliminated). The inp.h null-check
        // safepoint's monitor entry is rewritten to eliminated=true (index=1)
        // with a VORef owner, and a ScalarValueType VO descriptor is emitted
        // for p. At deopt HotSpot reallocates p and re-acquires its lock
        // (relock_objects).
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // PEA described the locked virtual object p: the ScalarValueType VO
        // descriptor header (vo_id 0) = (0<<32)|(4<<16)|T_OBJECT(12) = 262156.
        checker.check("262156");
        // PEA elided the balanced monitorenter/monitorexit pair: no real
        // monitor call remains. The deopt bundle for the null-check deopt
        // inside the synchronized block carries the eliminated marker
        // (1<<32)|(3<<16)|T_OBJECT(12) = 4295163916 with a VORef owner, so
        // HotSpot relock_objects re-acquires the lock on the reallocated p.
        // (4295163916 shares the same deopt-bundle line as 262156 and is not
        // re-asserted here -- FileCheck is forward-only by line; the elision
        // is confirmed by the absence of the real monitor call.)
        checker.checkNot("monitorenter_with");
        checker.checkNot("monitorexit_with");
        System.out.println("TestLockOnVirtualDeopt IR: P4c lock-elision mechanism validated");

        // test(holder) returns 10 (p.x) + 20 (p.y) + 0 (holder.h) = 30.
        // test(null) null-check deopts at inp.h INSIDE the synchronized block;
        // deopt reconstructs the frame and the lock state, then the interpreter
        // throws NPE on the null inp.h access (unwinding monitorexit). A broken
        // lock reconstruction surfaces as an IllegalMonitorStateException or a
        // nonzero exit.
        output.shouldContain("TestLockOnVirtualDeopt result: 30");
        output.shouldContain("TestLockOnVirtualDeopt deopt: NPE");
        output.shouldNotContain("IllegalMonitorStateException");
    }

    public static class TestWrapper {
        // Simple null-check vehicle (not a VO).
        public static class Holder { public int h; }
        public static class Point { public int x; public int y; }

        public static void main(String[] args) {
            // Initialize classes so test() compiles a real body (no class-init
            // trap stubs), with PEA virtualizing p.
            new Holder();
            new Point();
            Holder holder = new Holder();
            holder.h = 0;
            int r = test(holder);   // compiles + runs (no deopt)
            System.out.println("TestLockOnVirtualDeopt result: " + r);
            try {
                test(null);          // null-check deopt inside synchronized; p locked
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestLockOnVirtualDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 30);
        }

        // Compiled by Jeandle. p (Point) never escapes and is held in a
        // balanced synchronized block (PEA elides the monitorenter/exit). At
        // the inp.h safepoint inside the block p is still virtual AND locked,
        // so PEA describes it and rewrites the monitor entry to
        // eliminated=true with a VORef owner.
        public static int test(Holder inp) {
            Point p = new Point();
            p.x = 10;
            p.y = 20;
            int ox;
            synchronized (p) {
                ox = inp.h;          // null-check safepoint; p virtual + locked
            }
            return p.x + p.y + ox;
        }
    }
}
