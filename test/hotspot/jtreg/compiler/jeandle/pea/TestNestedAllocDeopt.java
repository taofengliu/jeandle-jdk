/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
 * License version 2 for more details (a copy is included in the LICENSE
 * file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary PEA allocation-bundle scanning (review §3 #10): a VO referenced
 *          only by ANOTHER allocation invoke's deopt bundle must be
 *          described there (Graal describes VOs in allocation frame states),
 *          not left for Pass-2 poison-RAUW. `a = new A(); b = new B();
 *          sink(b);` with a live across b's allocation: a is NeverEscapes
 *          and must be DESCRIBED in b's surviving allocation bundle; b is
 *          PartiallyEscapes (its invoke retained). The IR check verifies
 *          a's descriptor and VORef slot in b's allocation bundle; the
 *          behavioral check exercises a null-check deopt with a live.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestNestedAllocDeopt
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNestedAllocDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestNestedAllocDeopt$TestWrapper";
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

        // b is PartiallyEscapes (escapes via sink(b)): its allocation is
        // RETAINED in the optimized IR. a is NeverEscapes: eliminated, and
        // DESCRIBED in b's surviving allocation bundle — the ScalarValueType
        // header (vo_id 0) = (0<<32)|(4<<16)|T_OBJECT(12) = 262156 and the
        // VORefLocalType slot = (0<<32)|(8<<16)|12 = 524300. With the pre-fix
        // early-return at the allocation dispatch, a's bundle slot stayed a
        // raw OrigAlloc and Pass 2 poisoned it.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        checker.check("new_instance");
        checker.check("262156");
        checker.check("524300");

        // Behavioral: test(null) null-check deopts at inp.h AFTER both
        // allocations; a is reconstructed from the VO descriptor and the
        // interpreter throws NPE. test(holder) runs clean.
        output.shouldContain("TestNestedAllocDeopt result: 13");
        output.shouldContain("TestNestedAllocDeopt deopt: NPE");
    }

    public static class TestWrapper {
        public static class Holder { public int h; }
        public static class A { public int x; public int y; }
        public static class B { }

        static B global; // opaque escape target
        // Not inlined (dontinline); passing b here makes it PartiallyEscapes.
        static void sink(B b) { global = b; }

        public static void main(String[] args) {
            new Holder(); new A(); new B(); // init classes
            Holder holder = new Holder();
            holder.h = 3;
            int r = test(holder);  // compiles + runs (no deopt)
            System.out.println("TestNestedAllocDeopt result: " + r);
            try {
                test(null);        // null-check deopt at inp.h; a live
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestNestedAllocDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 13);
        }

        // Compiled by Jeandle. a is allocated first and live across b's
        // allocation (referenced by b's allocation deopt bundle) — the #10
        // shape. b escapes via sink(b), so b's invoke is retained with a's
        // descriptor in its bundle.
        public static int test(Holder inp) {
            A a = new A();
            a.x = 10;
            B b = new B();          // b's allocation bundle references a
            sink(b);                // b escapes (PartiallyEscapes)
            a.y = inp.h;            // null-check safepoint; a live
            return a.x + a.y;
        }
    }
}
