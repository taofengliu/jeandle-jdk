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
 * @summary PEA loop-carried object: an object referenced by a loop-header
 *          PHI (the SSA phi for the local) must stay virtual across the
 *          loop. The iter-0 header merge decides on the forward-edge
 *          incoming only (the not-yet-visited back edge is unknown, not a
 *          divergence), taking Graal's Case B instead of materializing the
 *          object at the preheader.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestLoopCarriedObjectPEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLoopCarriedObjectPEA {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestLoopCarriedObjectPEA$TestWrapper";
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

        // p is carried around the loop by the SSA phi for the local and never
        // escapes: the allocation is fully eliminated.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", int.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.checkNot("jeandle.new_instance");

        output.shouldContain("TestLoopCarriedObjectPEA result: 45");
    }

    public static class TestWrapper {
        public static class Point { public int x; }

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub).
            new Point();
            int r = test(10);
            System.out.println("TestLoopCarriedObjectPEA result: " + r);
            Asserts.assertEquals(r, 45);
        }

        // Compiled by Jeandle. p is a loop-carried virtual object: the loop
        // header's pointer PHI carries it around the back edge (identity
        // carry), so it must stay virtual — the allocation, the field
        // stores, and the field loads all fold away.
        public static int test(int n) {
            Point p = new Point();
            int sum = 0;
            for (int i = 0; i < n; i++) {
                p.x = i;
                sum += p.x;
            }
            return sum;
        }
    }
}
