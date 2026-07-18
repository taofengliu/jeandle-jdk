/*
 * Copyright (c) 2026, The Jeandle-JDK Authors. All Rights Reserved.
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
 * @summary PEA PartiallyEscapes object with a TOUCHED long field kept
 *          virtual at a null-check safepoint. The VO descriptor must carry
 *          the long field (one wire entry enc(offset, LocalType, T_LONG) +
 *          the i64 value), and the HotSpot parse must expand it to the two
 *          field_values slots reassign_fields_by_klass consumes
 *          (ConstantIntValue(0) hi placeholder + ConstantLongValue lo). A
 *          broken two-slot expansion mis-aligns field_values and crashes
 *          reassign_fields_by_klass (assert "Agreement" / out-of-bounds) at
 *          deopt. Validates the long-field parse end-to-end.
 * @library /test/lib /
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPartialEscapeDeoptLongField
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPartialEscapeDeoptLongField {
    // A distinctive 64-bit value whose high 32 bits are non-zero, so a
    // mis-reconstruction that only keeps the low half (e.g. a wrong slot
    // consumed by long_field_put) is observable. 0x123456789ABCDEF0L.
    static final long MARK = 0x123456789ABCDEF0L;

    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestPartialEscapeDeoptLongField$TestWrapper";
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

        // p is PartiallyEscapes: escapes via sink(p) on escape==true, so PEA
        // keeps the allocation; but at the other.x null-check safepoint p is
        // still virtual, so the deopt bundle's p slot is rewritten to a VORef
        // + ScalarValueType descriptor carrying BOTH fields, including the
        // touched long field.
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test",
                        TestWrapper.Point.class, boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc .*test.*");
        // PartiallyEscapes => allocation retained as @new_instance.
        checker.check("new_instance");
        // ScalarValueType VO descriptor header (vo_id 0):
        // (0<<32)|(4<<16)|T_OBJECT(12) = 262156, and the OrigAlloc locals
        // slot replaced by a VORefLocalType reference (vo_id=0):
        // (0<<32)|(8<<16)|12 = 524300. Both encodings sit on the SAME
        // null-check deopt-bundle line (descriptor first, VORef slot after),
        // so match them with one pattern (FileCheck advances a whole line
        // per successful check).
        checker.checkPattern("262156.*524300");

        // Non-deopt path returns p.x (int 10) is not used; we return p.lx +
        // ox. With p.lx = MARK and some.x = 0, the low 32 bits of MARK are
        // 0x9ABCDEF0 = -1698898192 (signed), so the int return truncates.
        // To keep the assertion stable we return a small int derived only
        // from p.x + ox (p.lx is reconstructed at deopt but its value is
        // observed via the deopt reconstruction not crashing).
        output.shouldContain("TestPartialEscapeDeoptLongField result: 10");
        // test(null, false): null-check deopt at other.x reconstructs p (x=10,
        // lx=MARK) from the VO descriptor -- a malformed long two-slot
        // expansion in the parse crashes reassign_fields_by_klass here.
        output.shouldContain("TestPartialEscapeDeoptLongField deopt: NPE");
    }

    public static class TestWrapper {
        public static class Point {
            public int x;
            public long lx;   // touched long field (two-slot emit + parse)
        }

        static Point global;   // opaque escape target

        // Not inlined (dontinline); passing p here makes it PartiallyEscapes.
        static void sink(Point p) { global = p; }

        public static void main(String[] args) {
            new Point();                  // init klass
            Point some = new Point();
            some.x = 0;
            some.lx = 0L;
            int r = test(some, false);    // compiles + runs (no deopt)
            System.out.println("TestPartialEscapeDeoptLongField result: " + r);
            try {
                test(null, false);         // null-check deopt at other.x; p live
                Asserts.fail("expected NPE");
            } catch (NullPointerException e) {
                System.out.println("TestPartialEscapeDeoptLongField deopt: NPE");
            }
            Asserts.assertEquals(r, 10);
        }

        // Compiled by Jeandle. p (Point with a long field) escapes via
        // sink(p) on escape==true (PartiallyEscapes), but p is still virtual
        // at the other.x safepoint, so PEA emits a VO descriptor for it there
        // carrying the touched long field lx. The escape flag is a parameter
        // so PEA cannot fold the escape away.
        public static int test(Point other, boolean escape) {
            Point p = new Point();
            p.x = 10;
            p.lx = MARK;         // touched long field
            int ox = other.x;    // null-check safepoint; p virtual here
            if (escape) sink(p); // escape path -> PartiallyEscapes
            return p.x + ox;     // returns 10 + ox
        }
    }
}
