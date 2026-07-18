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
 * @summary PEA virtual Object[] with aastore: jeandle.array_store_check is
 *          lower-phase="1" so the call reaches PEA and is folded (elided for
 *          a provably-compatible store). At lower-phase="0" the expanded
 *          body's raw klass-header load marked the array ineligible and
 *          killed array virtualization for every reference array store.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestArrayStoreCheckVirtualArray
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayStoreCheckVirtualArray {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestArrayStoreCheckVirtualArray$TestWrapper";
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

        // The String[] allocation is virtualized even though the body does
        // aastores: the array_store_check calls are elided, the element
        // stores are eliminated, and the element loads fold.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test"),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.checkNot("jeandle.new_array");

        output.shouldContain("TestArrayStoreCheckVirtualArray result: 2");
    }

    public static class TestWrapper {
        public static void main(String[] args) {
            int r = test();
            System.out.println("TestArrayStoreCheckVirtualArray result: " + r);
            Asserts.assertEquals(r, 2);
        }

        // Compiled by Jeandle. The array never escapes; every aastore is
        // provably type-compatible (String constants into a String[]), so
        // the whole allocation is scalar-replaced.
        public static int test() {
            String[] arr = new String[2];
            arr[0] = "a";
            arr[1] = "b";
            return arr[0].length() + arr[1].length();
        }
    }
}
