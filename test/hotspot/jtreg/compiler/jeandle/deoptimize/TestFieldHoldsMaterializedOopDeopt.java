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
 * @summary GC-liveness: a never-escaping VO whose reference field holds a REAL
 *          (non-virtual) oop is still described at a deopt safepoint. The field
 *          value is the live oop, which RS4GC keeps GC-live/relocatable as a
 *          deopt-bundle operand and HotSpot reads back as LocationValue(oop);
 *          deopt reallocates the VO and writes the (relocated) oop into the
 *          field. No crash, correct reconstruction.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.deoptimize.TestFieldHoldsMaterializedOopDeopt
 */

package compiler.jeandle.deoptimize;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestFieldHoldsMaterializedOopDeopt {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.deoptimize.TestFieldHoldsMaterializedOopDeopt$TestWrapper";
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

        // obj (Outer) never escapes -> scalar-replaced and described. Its
        // reference field `ref` holds `ext`, a non-virtual parameter oop; the
        // field state is a scalar pointer to a wide oop. The descriptor (vo_id 0
        // header = (0<<32)|(4<<16)|T_OBJECT(12) = 262156) is emitted with `ref`
        // as a live-oop field value that RS4GC relocates.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", TestWrapper.Holder.class,
                                            TestWrapper.Ext.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // obj is eliminated (NeverEscapes); only its descriptor remains.
        checker.checkNot("new_instance");
        checker.check("262156");

        // test(holder, ext) returns obj.x (7) + ext.v (4) + holder.h (0) = 11.
        // test(null, ext) null-check deopts at inp.h with obj virtual and its
        // `ref` field = ext; deopt reallocates obj and writes the live oop ext
        // into ref, then the interpreter throws NPE on the null inp.h access.
        output.shouldContain("TestFieldHoldsMaterializedOopDeopt result: 11");
        output.shouldContain("TestFieldHoldsMaterializedOopDeopt deopt: NPE");
    }

    public static class TestWrapper {
        public static class Holder { public int h; }
        public static class Outer { public Ext ref; public int x; }
        public static class Ext { public int v; }

        public static void main(String[] args) {
            new Holder();
            new Outer();
            new Ext();
            Holder holder = new Holder();
            holder.h = 0;
            Ext ext = new Ext();
            ext.v = 4;
            int r = test(holder, ext);   // compiles + runs (no deopt)
            System.out.println("TestFieldHoldsMaterializedOopDeopt result: " + r);
            try {
                test(null, ext);          // null-check deopt at inp.h; obj virtual
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestFieldHoldsMaterializedOopDeopt deopt: NPE");
            }
            Asserts.assertEquals(r, 11);
        }

        // Compiled by Jeandle. obj never escapes (scalar-replaced). Its `ref`
        // field holds `ext`, a non-virtual parameter oop, so at the inp.h
        // safepoint obj is virtual with a scalar-pointer field -- the
        // GC-liveness path describes obj with `ref` as a live oop.
        //
        // NOTE: the sibling lit test 650_field_holds_materialized_vo.ll covers
        // the MaterializedRef shape (a field holding an ESCAPED VO's OrigAlloc)
        // at the IR level. TODO(pea-core): the end-to-end shape where a
        // NeverEscapes VO references an escaped object allocated in the same
        // method is left undescribed at that allocation's safepoint; the scalar
        // (external parameter) shape used here avoids it and exercises the same
        // GC-liveness descriptor path.
        public static int test(Holder inp, Ext ext) {
            Outer obj = new Outer();    // NeverEscapes -> vo_id 0
            obj.x = 7;
            obj.ref = ext;             // GC-liveness: field holds external oop
            int o = inp.h;             // null-check safepoint; obj virtual, ref=ext
            return obj.x + ext.v + o;  // 7 + 4 + 0 = 11
        }
    }
}
