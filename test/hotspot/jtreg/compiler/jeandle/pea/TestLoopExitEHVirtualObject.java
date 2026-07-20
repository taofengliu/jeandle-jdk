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
 * @summary PEA deopt-within-reach: a loop-local object that never escapes,
 *          inside a loop wrapped by a try/catch. The loop body's invoke has an
 *          unwind edge leaving the loop to a catch handler (a loop exit to an
 *          EH-pad). The EH handler runs with real state during exception
 *          unwind, but does not observe the loop-local object, so PEA keeps the
 *          object scalar-replaced (NeverEscapes) and eliminates the allocation.
 *          Previously processLoopExit conservatively force-materialized every
 *          still-virtual VO at such an EH-pad exit; that force was vestigial
 *          under reuse-OrigAlloc and has been removed. Verifies correct result
 *          and that the allocation is scalar-replaced in the optimized IR.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestLoopExitEHVirtualObject
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestLoopExitEHVirtualObject {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestLoopExitEHVirtualObject$TestWrapper";
        ArrayList<String> command_args = new ArrayList<>(List.of(
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

        // The loop-local Holder is NeverEscapes and must be scalar-replaced:
        // no jeandle.new_instance survives in the optimized IR. (Before the
        // deopt-within-reach fix, processLoopExit force-materialized it at the
        // EH-pad loop exit and the allocation survived.)
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", int.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.checkNot("jeandle.new_instance");

        output.shouldContain("TestLoopExitEHVirtualObject result: 90");
    }

    public static class TestWrapper {
        public static class Holder { public int v; }

        public static void main(String[] args) {
            int r = test(10);
            System.out.println("TestLoopExitEHVirtualObject result: " + r);
            Asserts.assertEquals(r, 90);
        }

        // Compiled by Jeandle. Holder h is loop-local and NeverEscapes. The
        // try/catch WRAPS the loop, so mayThrow's invoke has an unwind edge
        // leaving the loop to the catch (a loop exit to an EH-pad). In steady
        // state mayThrow does not throw, so the catch is never entered and the
        // result is sum(i*2 for i in 0..9) = 90. h must stay scalar-replaced.
        public static int test(int n) {
            int sum = 0;
            try {
                for (int i = 0; i < n; i++) {
                    Holder h = new Holder();
                    h.v = i * 2;
                    sum += mayThrow(h.v);
                }
            } catch (RuntimeException e) {
                sum += 1000;
            }
            return sum;
        }

        static int mayThrow(int x) { return x; }
    }
}
