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
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Harness smoke test: confirms the LLVM PEA trace/stats/dump channels
 *          surface on the child-VM stderr (via -XX:JeandleLLVMOptions=...) and
 *          that PEATestUtils can parse them. Validates the foundation the rest
 *          of the PEA suite builds on.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.pea.PEATestUtils
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAHarnessSmoke
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.file.Path;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;

public class TestPEAHarnessSmoke {
    public static void main(String[] args) throws Exception {
        Method target = TestWrapper.class.getMethod("test");
        PEATestUtils.Run run = PEATestUtils.run("compiler.jeandle.pea.TestPEAHarnessSmoke$TestWrapper")
                .target(target)
                .llvmOptions(PEATestUtils.peaLLVMOptions(target));
        Path dumpDir = run.dumpDir();
        OutputAnalyzer out = run.run();

        // The three PEA channels must reach the driver via stderr.
        out.shouldContain("PEA: EliminateAllocation");
        out.shouldContain(";; PEA stats @");
        out.shouldContain(";; PEA-DUMP after iter=");

        // PEATestUtils parses the stats line + effect trace for the target method.
        PEATestUtils.assertStats(out, target, 1, 0, 0);
        PEATestUtils.assertEffect(out, "EliminateAllocation");
        PEATestUtils.assertEffect(out, "ReplaceLoad");

        // Rigorous NeverEscape oracle via the PEA target: the @jeandle.new_instance
        // is present before PEA and eliminated (0) after PEA. (The dumpDir-based
        // PEABody is still exercised here to keep that path covered.)
        PEATestUtils.assertNeverEscapes(out, target);
        PEATestUtils.PEABody body = new PEATestUtils.PEABody(dumpDir, target, true);
        body.assertAbsent("jeandle.new_instance");

        out.shouldContain("TestPEAHarnessSmoke result: 7");
        System.out.println("TestPEAHarnessSmoke: harness OK");
    }

    public static class TestWrapper {
        public static class Point { public int x; public int y; }
        public static void main(String[] args) {
            new Point();
            int r = test();
            System.out.println("TestPEAHarnessSmoke result: " + r);
            Asserts.assertEquals(r, 7);
        }
        // NeverEscape: p is scalar-replaced (EliminateAllocation + ReplaceLoad).
        public static int test() {
            Point p = new Point();
            p.x = 3;
            p.y = 4;
            return p.x + p.y;
        }
    }
}
