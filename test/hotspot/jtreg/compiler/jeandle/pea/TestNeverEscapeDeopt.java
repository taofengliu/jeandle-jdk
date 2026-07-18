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
 * @summary PEA never-escape object described by a VO deopt descriptor and
 *          reconstructed at deopt.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestNeverEscapeDeopt
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNeverEscapeDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestNeverEscapeDeopt$TestWrapper";
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

        // p is live across the other.x null-check, so its state is carried as a
        // VO descriptor (ScalarValueType header, vo_id in the index field) in
        // that deopt bundle, and the OrigAlloc jeandle.new_instance is
        // eliminated. Verify on the post-optimization IR dump.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Point.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.checkNot("jeandle.new_instance");
        // ScalarValueType header (vo_id 0): (0<<32)|(4<<16)|T_OBJECT(12) = 262156
        checker.checkPattern("262156");

        // test(some) returns 30 = 10 (p.x) + 20 (p.y) + 0 (some.x). test(null)
        // null-check deopts at other.x; the deopt reconstructs test()'s frame,
        // reallocating p with x=10, y=20 from the VO descriptor. A broken
        // reconstruction (or crash) makes the test fail / exit nonzero.
        output.shouldContain("TestNeverEscapeDeopt result: 30");
        output.shouldContain("TestNeverEscapeDeopt deopt: NPE");
    }

    public static class TestWrapper {
        public static class Point { public int x; public int y; }

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub), with PEA virtualizing p.
            new Point();
            Point some = new Point();
            some.x = 0;
            int r = test(some);          // compiles + runs (no deopt)
            System.out.println("TestNeverEscapeDeopt result: " + r);
            try {
                test(null);              // null-check deopt at other.x; p live
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestNeverEscapeDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 30);
        }

        // Compiled by Jeandle. p (Point) never escapes: its fields are
        // scalar-replaced and p is referenced only as a live local across the
        // other.x access. PEA emits a VO descriptor for p in that deopt bundle.
        public static int test(Point other) {
            Point p = new Point();
            p.x = 10;
            p.y = 20;
            int ox = other.x;   // null-check safepoint; p is live here
            return p.x + p.y + ox;
        }
    }
}
