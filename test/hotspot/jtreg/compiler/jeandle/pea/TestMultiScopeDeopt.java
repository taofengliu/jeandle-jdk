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
 *          CALLER is live across an INLINED callee's safepoint, so it is
 *          referenced from the ROOT (outer) scope of that safepoint's deopt
 *          bundle. It must still be virtualized: the VO descriptor is emitted
 *          into the ROOT scope's VO section (the deopt-point-level object
 *          pool) and the outer-scope locals slot is rewritten to a VORef.
 *          Pre-fix (TODO(multi-scope-descriptors)) such a VO was banned and
 *          materialized at the call.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestMultiScopeDeopt
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestMultiScopeDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestMultiScopeDeopt$TestWrapper";
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

        // outer (Outer) is allocated by the CALLER (test) and never escapes:
        // its allocation is eliminated and it stays virtual across the inlined
        // inlinee's null-check safepoint. That safepoint's deopt bundle has
        // TWO scopes: the ROOT scope is test@15 (the call site) and the inner
        // scope is inlinee@1. outer is referenced from the root scope's
        // locals, so (post multi-scope-descriptors fix) its VO descriptor is
        // emitted into the ROOT scope's VO section and the root locals slot
        // is rewritten to a VORef. Verified on the post-optimization dump.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // The allocation is eliminated (NeverEscapes): no new_instance remains.
        checker.checkNot("new_instance");
        // The whole deopt bundle is one line. Lock the layout of the root
        // scope section with a single ordered pattern:
        //   262156  VO header of outer: vo_id 0, instance -> (0<<32)|(4<<16)|12
        //   524300  VORef local slot in the ROOT scope: (0<<32)|(8<<16)|12,
        //           i.e. test's local l1 (outer) rewritten to a VORef to vo 0
        //   393233  MethodType marker (6<<16)|17 that starts the NEXT (inner)
        //           scope (inlinee@1) -- proves the bundle is multi-scope and
        //           that both the descriptor and the VORef sit in the ROOT
        //           scope's section, before the inner scope begins.
        checker.checkPattern("262156.*524300.*393233");

        output.shouldContain("TestMultiScopeDeopt result: 7");
        output.shouldContain("TestMultiScopeDeopt deopt: 7");
    }

    public static class TestWrapper {
        public static class Holder { public int h; }
        public static class Outer { public int ox; }

        public static void main(String[] args) {
            new Holder();
            new Outer();
            Holder holder = new Holder();
            holder.h = 0;
            int r = test(holder);   // compiles + runs (no deopt)
            System.out.println("TestMultiScopeDeopt result: " + r);
            int d = test(null);     // null-check deopt in inlined inlinee
            System.out.println("TestMultiScopeDeopt deopt: " + d);
            Asserts.assertEquals(r, 7);
            Asserts.assertEquals(d, 7);
        }

        public static int test(Holder inp) {
            Outer outer = new Outer();
            outer.ox = 7;
            int o;
            try {
                o = inlinee(inp);   // inlined; null-check deopt when inp==null
            } catch (NullPointerException e) {
                o = 0;              // interpreter continues with reallocated outer
            }
            return outer.ox + o;    // 7 + 0 = 7
        }

        public static int inlinee(Holder h) {
            return h.h;             // null-check safepoint with a deopt bundle
        }
    }
}
