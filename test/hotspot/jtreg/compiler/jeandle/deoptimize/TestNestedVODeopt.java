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
 * @summary PEA transitive VORef field deopt: a never-escaping VO with a
 *          reference field that points at another never-escaping VO. Both are
 *          virtual at a null-check safepoint, so both are described by VO
 *          descriptors and the reference field is encoded as a VORef. At deopt
 *          both are reallocated and outer.inner is re-linked to the
 *          reallocated inner.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.deoptimize.TestNestedVODeopt
 */

package compiler.jeandle.deoptimize;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNestedVODeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.deoptimize.TestNestedVODeopt$TestWrapper";
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

        // outer (Outer) and inner (Inner) never escape: their fields are
        // scalar-replaced and both allocations are eliminated. outer's
        // reference field `inner` resolves to the VO inner, so it is encoded
        // as a VORef field (value = inner's vo-id) and inner gets its own
        // descriptor (referenced by id from outer's field) -- the transitive
        // closure is fully described. The test LOADS THROUGH the reference
        // field (`Inner tmp = outer.inner`), keeping `tmp` (= inner's
        // identity) live across the null-check safepoint; the load folds to
        // inner's identity and PEA keeps both VOs virtual (the deopt bundle's
        // `tmp` slot is rewritten to a VORef to inner). Verified on the
        // post-optimization dump.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // Both allocations eliminated (NeverEscapes): no new_instance remains.
        checker.checkNot("new_instance");
        // Both VO descriptors are emitted on the null-check safepoint's deopt
        // bundle line. outer is vo_id 0 -> header (0<<32)|(4<<16)|T_OBJECT(12)
        // = 262156; inner is vo_id 1 -> header (1<<32)|(4<<16)|12 = 4295229452.
        // Two descriptors on one bundle line proves the transitive closure.
        checker.checkPattern("262156.*4295229452|4295229452.*262156");

        // test(holder) returns 7 (outer.ox) + 5 (inner.ix) + 0 (holder.h) = 12.
        // test(null) null-check deopts at inp.h; deopt reallocates BOTH outer
        // and inner and re-links outer.inner to the reallocated inner, then
        // the interpreter throws NPE on the null inp.h access.
        output.shouldContain("TestNestedVODeopt result: 12");
        output.shouldContain("TestNestedVODeopt deopt: NPE");
    }

    public static class TestWrapper {
        // Simple null-check vehicle (not a VO).
        public static class Holder { public int h; }
        public static class Outer { public Inner inner; public int ox; }
        public static class Inner { public int ix; }

        public static void main(String[] args) {
            // Initialize classes so test() compiles a real body (no class-init
            // trap stubs), with PEA virtualizing outer and inner.
            new Holder();
            new Outer();
            new Inner();
            Holder holder = new Holder();
            holder.h = 0;
            int r = test(holder);   // compiles + runs (no deopt)
            System.out.println("TestNestedVODeopt result: " + r);
            try {
                test(null);          // null-check deopt at inp.h; outer+inner live
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestNestedVODeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 12);
        }

        // Compiled by Jeandle. outer (Outer) and inner (Inner) never escape.
        // outer.inner holds inner, so PEA describes both with a VORef field
        // linking them. The test loads through the reference field (tmp =
        // outer.inner) and keeps tmp live across the inp.h safepoint; the load
        // folds to inner's identity, so tmp's deopt slot becomes a VORef to
        // inner and both VOs stay virtual.
        public static int test(Holder inp) {
            Outer outer = new Outer();   // first alloc -> vo_id 0
            Inner inner = new Inner();   // second alloc -> vo_id 1
            inner.ix = 5;
            outer.inner = inner;         // VORef field
            outer.ox = 7;
            Inner tmp = outer.inner;     // load-through-ref; tmp = inner identity
            int o = inp.h;               // null-check safepoint; outer+tmp live
            return outer.ox + tmp.ix + o; // 7 + 5 + 0 = 12
        }
    }
}
