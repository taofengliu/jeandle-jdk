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
 * @summary PEA Case C at a non-header in-loop merge: two distinct virtual
 *          objects of the same class merge behind a conditional inside a
 *          loop. The synthesized merged VO must be stable across loop-
 *          fixpoint iterations (the CaseCVOCache covers every in-loop merge
 *          block, not just loop headers), or the fixpoint escalates to
 *          MATERIALIZE_ALL and both allocations survive.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestInLoopMergeCaseCPEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestInLoopMergeCaseCPEA {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestInLoopMergeCaseCPEA$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // Both per-iteration allocations are virtualized: the merged object
        // is read only through its (merged) field and never escapes.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", int.class, boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.checkNot("jeandle.new_instance");

        output.shouldContain("TestInLoopMergeCaseCPEA result: 10");
    }

    public static class TestWrapper {
        public static class Point { public int x; }

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub).
            new Point();
            int r = test(10, true);
            System.out.println("TestInLoopMergeCaseCPEA result: " + r);
            Asserts.assertEquals(r, 10);
        }

        // Compiled by Jeandle. Each iteration allocates two Points and
        // merges them behind the (opaque) flag c; only the merged field
        // value is observed. Case C synthesizes one merged VO; the field
        // merge phi carries x (1 on the a-arm, 2 on the b-arm).
        public static int test(int n, boolean c) {
            int sum = 0;
            for (int i = 0; i < n; i++) {
                Point a = new Point();
                a.x = 1;
                Point b = new Point();
                b.x = 2;
                Point p = c ? a : b;
                sum += p.x;
            }
            return sum;
        }
    }
}
