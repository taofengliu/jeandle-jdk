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
 * @summary PEA PartiallyEscapes object: kept virtual at a null-check
 *          safepoint (VO descriptor emitted) while escaping on a later
 *          conditional path (OrigAlloc retained + field stores replayed).
 *          Validates end-to-end deopt reconstruction for a partial escape.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPartialEscapeDeopt
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPartialEscapeDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestPartialEscapeDeopt$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                "-XX:CompileCommand=dontinline," + wrapper + "::sink",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // p is PartiallyEscapes: it escapes via sink(p) on the escape==true
        // branch, so PEA KEEPS the allocation (reuse-OrigAlloc) and the object
        // is materialized -- the slow-path @new_instance allocation call is
        // retained in the optimized IR (contrast TestNeverEscapeDeopt, where
        // the allocation is fully eliminated). But at the other.x null-check
        // safepoint (visited before the escape), p is still virtual, so the
        // deopt bundle's p slot is rewritten to a VORef + ScalarValueType
        // descriptor (klass + x=10, y=20).
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test",
                        TestWrapper.Point.class, boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // PartiallyEscapes => allocation retained as @new_instance (NeverEscapes
        // eliminates it). Searched after the define line, so this matches the
        // slow-path invoke inside the body, not the pre-define declaration.
        checker.check("new_instance");
        // ScalarValueType VO descriptor header (vo_id 0):
        // (0<<32)|(4<<16)|T_OBJECT(12) = 262156, and the OrigAlloc locals
        // slot replaced by a VORefLocalType reference (vo_id=0):
        // (0<<32)|(8<<16)|12 = 524300. Both encodings sit on the SAME
        // null-check deopt-bundle line (descriptor first, VORef slot after),
        // so match them with one pattern (FileCheck advances a whole line
        // per successful check).
        checker.checkPattern("262156.*524300");

        // test(some, false) returns 10 (p.x) + 20 (p.y) + 0 (some.x) = 30.
        // test(null, false) null-check deopts at other.x; the deopt
        // reconstructs p (x=10, y=20) from the VO descriptor, then the
        // interpreter throws NPE on the null other.x access. A broken
        // reconstruction (or crash) makes the test fail / exit nonzero.
        output.shouldContain("TestPartialEscapeDeopt result: 30");
        output.shouldContain("TestPartialEscapeDeopt deopt: NPE");
    }

    public static class TestWrapper {
        public static class Point { public int x; public int y; }

        static Point global;   // opaque escape target

        // Not inlined (dontinline); passing p here makes it PartiallyEscapes.
        static void sink(Point p) { global = p; }

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub), with PEA virtualizing p.
            new Point();
            Point some = new Point();
            some.x = 0;
            int r = test(some, false);   // compiles + runs (no deopt)
            System.out.println("TestPartialEscapeDeopt result: " + r);
            try {
                test(null, false);        // null-check deopt at other.x; p live
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestPartialEscapeDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 30);
        }

        // Compiled by Jeandle. p (Point) escapes via sink(p) on the
        // escape==true branch (PartiallyEscapes: OrigAlloc kept, field stores
        // replayed before the escape), but p is still virtual at the other.x
        // safepoint, so PEA emits a VO descriptor for it there. The escape
        // flag is a parameter so PEA cannot fold the escape away.
        public static int test(Point other, boolean escape) {
            Point p = new Point();
            p.x = 10;
            p.y = 20;
            int ox = other.x;     // null-check safepoint; p virtual here
            if (escape) sink(p);  // escape path -> PartiallyEscapes
            return p.x + p.y + ox;
        }
    }
}
