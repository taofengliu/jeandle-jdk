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
 * @summary PEA record-before-materialize fix (review §3 #6): a VO that is a
 *          real call argument must be MATERIALIZED at the call (Graal
 *          processNodeInputs before processNodeWithState), never described
 *          as virtual in the same call's deopt bundle. Deopt DURING the call
 *          must preserve one Java identity: the callee's field write to the
 *          argument is visible to the caller after frame reconstruction.
 *          The pre-fix bug described the VO as virtual at the escaping call,
 *          so HotSpot reallocated a NEW object for the caller while the
 *          callee held the real one (identity split).
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox jdk.test.lib.Asserts
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestDuringCallDeoptIdentity
 */

package compiler.jeandle.pea;

import compiler.jeandle.fileCheck.FileCheck;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestDuringCallDeoptIdentity {
    public static void main(String[] args) throws Exception {
        String dump_path = System.getProperty("user.dir");
        String wrapper = "compiler.jeandle.pea.TestDuringCallDeoptIdentity$TestWrapper";
        ArrayList<String> command_args = new ArrayList<String>(List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+JeandleDoPEA",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump_path,
                "-XX:+PrintNMethods",
                "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
                "-XX:CompileCommand=compileonly," + wrapper + "::test",
                "-XX:CompileCommand=dontinline," + wrapper + "::bump",
                wrapper));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command_args);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);
        output.shouldHaveExitValue(0);

        // IR check: p is PartiallyEscapes (it escapes as bump's argument), so
        // its allocation is RETAINED in the optimized IR, and NO VO descriptor
        // is emitted at the bump call (the bundle slot keeps the live oop).
        FileCheck checker = new FileCheck(dump_path,
                TestWrapper.class.getMethod("test", boolean.class),
                /*optimized=*/true);
        checker.checkPattern("define hotspotcc i32 .*test.*");
        // PartiallyEscapes => allocation retained as @new_instance.
        checker.check("new_instance");
        // ScalarValueType VO descriptor header (vo_id 0) would be
        // (0<<32)|(4<<16)|T_OBJECT(12) = 262156; VORefLocalType slot would be
        // (0<<32)|(8<<16)|12 = 524300. Neither must appear: p is a real
        // argument, so it is materialized at the call, not described.
        checker.checkNot("262156");
        checker.checkNot("524300");

        // Behavioral: test(true) runs bump(p) which calls
        // WhiteBox.deoptimizeAll() — the compiled test frame is rebuilt from
        // the deopt info AT THE bump CALL while bump is on the stack. With
        // the fix, the rebuilt caller's p IS the real object bump wrote to
        // (p.x == 1). The pre-fix identity split would give p.x == 0 (the
        // write went to the real object; the caller got a fresh reallocated
        // Point).
        output.shouldContain("TestDuringCallDeoptIdentity identity: OK (p.x == 1)");
    }

    public static class TestWrapper {
        public static class Point { public int x; }

        // One-shot guard so the SECOND bump invocation (the interpreter
        // re-executes the call after the caller's frame is rebuilt) does not
        // write again and mask the identity check.
        static boolean written = false;

        public static void main(String[] args) {
            // Initialize Point so test() compiles a real body (no class-init
            // trap stub), with PEA virtualizing p until the bump argument.
            new Point();
            int r = test(false);  // compiles test with PEA (no deopt inside bump)
            Asserts.assertEquals(r, 0);
            r = test(true);       // bump deoptimizes all frames, then writes p.x = 1
            Asserts.assertEquals(r, 1);
            System.out.println("TestDuringCallDeoptIdentity identity: OK (p.x == 1)");
        }

        // Not inlined: bump is a real call. Written once (before deopt), so
        // the rebuilt caller's re-invocation skips it.
        static void bump(Point p, boolean deopt) {
            if (deopt && !written) {
                jdk.test.whitebox.WhiteBox.getWhiteBox().deoptimizeAll();
                written = true;
                p.x = 1;   // write to the REAL object the caller passed
            }
        }

        // Compiled by Jeandle+PEA. p is virtual until it escapes as bump's
        // argument; with the fix it is materialized at the call (never
        // described as virtual in the same call's deopt bundle).
        public static int test(boolean deopt) {
            Point p = new Point();
            bump(p, deopt);
            return p.x;
        }
    }
}
