/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only,
 * as published by the Free Software Foundation.
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
 * @summary Regression coverage for the PartiallyEscapes materialization path
 *          touched by the Minor/robustness fixes:
 *           - #8 MaterializeEffect::apply now gates Ctx.Changed on whether
 *             applyMaterialize actually emitted a store/lock. A mis-gate that
 *             dropped a needed materialization across the iterative driver's
 *             rounds would miscompile this (the escape-path replayed fields
 *             would be lost). The allocation must survive (PartiallyEscapes)
 *             and the result must be correct.
 *           - #11 materializeAndBuildPhi's clean-bail guard: p is virtual on
 *             the no-escape branch and materialized on the escape branch, so
 *             the post-branch merge builds a materialized-object PHI.
 *           - #18c the allocation invoke's unwind-edge drop asserts
 *             Normal != Unwind (NeverEscapes inner objects exercise this too).
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPartiallyEscapesMaterializeConvergence
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPartiallyEscapesMaterializeConvergence {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper =
            "compiler.jeandle.pea.TestPartiallyEscapesMaterializeConvergence$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                "-XX:CompileCommand=dontinline," + wrapper + "::sink",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // p is PartiallyEscapes: it escapes via sink(p) on the escape branch,
        // so PEA keeps the allocation (reuse-OrigAlloc) and replays the field
        // stores before the escape. The slow-path @new_instance is retained in
        // the optimized IR (contrast a NeverEscapes object, which is fully
        // eliminated).
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.check("new_instance");

        // Both calls return 11 + 22 = 33 (field replay / fold must be correct
        // whether or not the escape branch was taken).
        output.shouldContain("TestPartiallyEscapesMaterializeConvergence result: 33");
    }

    public static class TestWrapper {
        public static class Point { public int x; public int y; }

        static Point global;   // opaque escape target

        // Not inlined (dontinline); passing p here makes it PartiallyEscapes.
        static void sink(Point p) { global = p; }

        public static void main(String[] args) {
            new Point();          // initialize the class
            int r = test(true);   // escape branch taken -> materialize + replay
            // Also drive the no-escape branch so the merge sees p virtual on
            // one arm and materialized on the other (materializeAndBuildPhi).
            test(false);
            System.out.println(
                "TestPartiallyEscapesMaterializeConvergence result: " + r);
            Asserts.assertEquals(r, 33);
        }

        // Compiled by Jeandle. p (Point) is written then conditionally escapes
        // via sink(p). On the escape branch p is PartiallyEscapes (OrigAlloc
        // kept, fields replayed before sink); on the no-escape branch p stays
        // virtual. The post-branch read merges the two states. `escape` is a
        // parameter so PEA cannot fold the escape away.
        public static int test(boolean escape) {
            Point p = new Point();
            p.x = 11;
            p.y = 22;
            if (escape) sink(p);
            return p.x + p.y;
        }
    }
}
