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
 * @summary PEA materialize-at-use for an unvirtualizable access: a virtual
 *          array stored into at a symbolic (parameter) index materializes
 *          the array and the stored value AT the store (Graal
 *          processNodeInputs), instead of the old bail-all that marked both
 *          objects ineligible function-wide. The value's tracked field
 *          store is eliminated at its original site and replayed immediately
 *          before the array store; the old bail-all kept it in place.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestSymbolicIndexStorePEA
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestSymbolicIndexStorePEA {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestSymbolicIndexStorePEA$TestWrapper";
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

        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", int.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // Both objects materialize at the symbolic array store =>
        // PartiallyEscapes => both allocations retained (reuse-OrigAlloc).
        checker.check("new_array");
        checker.check("new_instance");
        // p's tracked field store (p.x = 10) is eliminated at its original
        // site and replayed immediately before the array store. The replay
        // slot's SSA name (pea.matslot at the PEA pass) does not survive the
        // full Jeandle pipeline, so anchor on POSITION instead: the store of
        // 10 must appear AFTER the array store's element address is computed.
        // Under the old bail-all the store stayed at the instance-allocation
        // site, far EARLIER than array_element_address, so this ordering
        // fails pre-fix.
        checker.check("array_element_address");
        checker.check("store atomic i32 10");

        output.shouldContain("TestSymbolicIndexStorePEA result: 20");
    }

    public static class TestWrapper {
        public static class Point { public int x; }

        public static void main(String[] args) {
            new Point(); // initialize Point so test() has no class-init trap
            int r = test(2);
            System.out.println("TestSymbolicIndexStorePEA result: " + r);
            Asserts.assertEquals(r, 20);
        }

        // Compiled by Jeandle. arr is a virtual Point[]; p a virtual Point.
        // arr[i] = p at the parameter index i is an access PEA cannot track
        // (symbolic offset), so both arr and p materialize AT the store.
        public static int test(int i) {
            Point[] arr = new Point[4];
            Point p = new Point();
            p.x = 10;
            int t = p.x;   // folded to 10; stays folded under materialize-at-use
            arr[i] = p;    // symbolic-index store: materialization point
            return t + p.x;
        }
    }
}
