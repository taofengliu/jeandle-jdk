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
 * @summary PEA virtual-array deopt: a never-escaping int[] that is scalar-
 *          replaced is described at a deopt safepoint by a T_ARRAY VO
 *          descriptor (all elements, touched + default); HotSpot reallocates
 *          the array (length derived from the element count) and reassigns the
 *          elements. No crash, correct reconstruction.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.deoptimize.TestArrayDeopt
 */

package compiler.jeandle.deoptimize;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.deoptimize.TestArrayDeopt$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // arr (int[4]) never escapes -> scalar-replaced and described by a
        // T_ARRAY VO descriptor. Header (vo_id 0, ScalarValueType, T_ARRAY(13))
        // = (0<<32)|(4<<16)|13 = 262157. The descriptor carries all 4 elements
        // (indices 0,2 default; 1,3 touched), so HotSpot derives length 4.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.check("262157");

        // test(holder) returns arr[1] (100) + arr[3] (400) + holder.h (0) = 500.
        // test(null) null-check deopts at inp.h with arr virtual; deopt
        // reallocates arr (length 4) and reassigns its elements, then the
        // interpreter throws NPE. A broken reconstruction (wrong length / bad
        // elements) crashes or miscomputes.
        output.shouldContain("TestArrayDeopt result: 500");
        output.shouldContain("TestArrayDeopt deopt: NPE");
    }

    public static class TestWrapper {
        public static class Holder { public int h; }

        public static void main(String[] args) {
            new Holder();
            Holder holder = new Holder();
            holder.h = 0;
            int r = test(holder);   // compiles + runs (no deopt)
            System.out.println("TestArrayDeopt result: " + r);
            try {
                test(null);          // null-check deopt at inp.h; arr virtual
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestArrayDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 500);
        }

        // Compiled by Jeandle. arr (int[4]) never escapes and is scalar-replaced;
        // only indices 1 and 3 are stored. At the inp.h safepoint arr is virtual
        // and described by a T_ARRAY descriptor with all 4 elements.
        public static int test(Holder inp) {
            int[] arr = new int[4];   // NeverEscapes -> vo_id 0
            arr[1] = 100;
            arr[3] = 400;
            int o = inp.h;            // null-check safepoint; arr virtual
            return arr[1] + arr[3] + o; // 100 + 400 + 0 = 500
        }
    }
}
