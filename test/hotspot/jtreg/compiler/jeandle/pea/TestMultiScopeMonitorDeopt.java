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
 * @summary PEA multi-scope descriptors: a never-escaping VO allocated by the
 *          CALLER is the owner of a balanced synchronized block that spans an
 *          INLINED callee's safepoint, so the monitor entry with owner=VO sits
 *          in the ROOT (outer) scope's monitor section of that safepoint's
 *          deopt bundle. It must still be virtualized AND described: the VO
 *          descriptor is emitted into the ROOT scope's VO section (the
 *          deopt-point-level object pool) and the root monitor entry is
 *          rewritten to eliminated=true with a VORef owner, so HotSpot
 *          reallocates the VO and re-acquires its lock (relock_objects) at
 *          deopt. No IllegalMonitorStateException; the interpreter's unwinding
 *          monitorexit stays balanced.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestMultiScopeMonitorDeopt
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestMultiScopeMonitorDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestMultiScopeMonitorDeopt$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // outer (Outer) is allocated by the CALLER (test), never escapes, and
        // is the owner of a synchronized block spanning the inlined inlinee
        // call. PEA folds the balanced monitorenter/monitorexit pair on the
        // virtual receiver, so outer stays virtual AND locked across inlinee's
        // null-check safepoint. That safepoint's deopt bundle has TWO scopes:
        // the ROOT scope is test (the call site, holding one locked monitor on
        // outer) and the inner scope is inlinee@1. outer is referenced from
        // the root scope's monitor section, so (post multi-scope-descriptors
        // fix) its VO descriptor is emitted into the ROOT scope's VO section
        // and the root monitor entry is rewritten to eliminated=true with a
        // VORef owner. Verified on the post-optimization dump.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // The allocation is eliminated (NeverEscapes): no new_instance remains.
        checker.checkNot("new_instance");
        // PEA elided the balanced monitorenter/monitorexit pair: no real
        // monitor call remains in the optimized IR.
        checker.checkNot("monitorenter_with");
        checker.checkNot("monitorexit_with");
        // The whole deopt bundle is one line. Lock the layout of the root
        // scope section with a single ordered pattern:
        //   262156      VO header of outer: vo_id 0, instance ->
        //               (0<<32)|(4<<16)|T_OBJECT(12)
        //   4295163916  eliminated monitor entry in the ROOT scope's monitor
        //               section: (1<<32)|(3<<16)|T_OBJECT(12), followed by
        //               i32 <vo_id> + basic_lock -- outer's lock is elided and
        //               re-acquired by relock_objects at deopt
        //   393233      MethodType marker (6<<16)|17 that starts the NEXT
        //               (inner) scope (inlinee@1) -- proves the bundle is
        //               multi-scope and that both the descriptor and the
        //               eliminated monitor sit in the ROOT scope's section,
        //               before the inner scope begins.
        checker.checkPattern("262156.*4295163916.*393233");

        // test(holder) runs to completion: 7 + 0 (holder.h) = 7. test(null)
        // null-check deopts inside the inlined inlinee while outer is locked;
        // deopt reconstructs the caller frame, reallocates outer and re-locks
        // it, then the interpreter throws NPE on the null h.h access,
        // unwinding the monitorexit on the rebuilt outer (must stay balanced),
        // and the catch completes with outer.ox == 7. A broken relock surfaces
        // as an IllegalMonitorStateException or a nonzero exit.
        output.shouldContain("TestMultiScopeMonitorDeopt result: 7");
        output.shouldContain("TestMultiScopeMonitorDeopt deopt: 7");
        output.shouldNotContain("IllegalMonitorStateException");
    }

    public static class TestWrapper {
        // Simple null-check vehicle (not a VO).
        public static class Holder { public int h; }
        public static class Outer { public int ox; }

        public static void main(String[] args) {
            // Initialize classes so test() compiles a real body (no class-init
            // trap stubs), with PEA virtualizing outer.
            new Holder();
            new Outer();
            Holder holder = new Holder();
            holder.h = 0;
            int r = test(holder);   // compiles + runs (no deopt)
            System.out.println("TestMultiScopeMonitorDeopt result: " + r);
            int d = test(null);     // null-check deopt in inlined inlinee; outer locked
            System.out.println("TestMultiScopeMonitorDeopt deopt: " + d);
            Asserts.assertEquals(r, 7);
            Asserts.assertEquals(d, 7);
        }

        // Compiled by Jeandle. outer (Outer) never escapes and is the owner of
        // a balanced synchronized block spanning the inlined inlinee call, so
        // at inlinee's null-check safepoint the ROOT scope's monitor section
        // holds an entry with owner=outer. PEA elides the monitorenter/exit,
        // describes outer, and rewrites the root monitor entry to
        // eliminated=true with a VORef owner.
        public static int test(Holder inp) {
            Outer outer = new Outer();
            outer.ox = 7;
            int o;
            try {
                synchronized (outer) {
                    o = inlinee(inp);   // inlined; null-check deopt when inp==null
                }
            } catch (NullPointerException e) {
                o = 0;                  // interpreter: rebuilt + relocked outer
            }
            return outer.ox + o;        // 7 + 0 = 7
        }

        public static int inlinee(Holder h) {
            return h.h;                 // null-check safepoint with a deopt bundle
        }
    }
}
