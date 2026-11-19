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
 */

/*
 * @test
 * @summary Hot builtin throws must consider each inlined caller chain
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver compiler.jeandle.exception.TestInlineBuiltinThrow
 */

package compiler.jeandle.exception;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestInlineBuiltinThrow {
    private static final String CLASS = TestInlineBuiltinThrow.class.getName();
    private static volatile ArithmeticException exception;
    private static volatile Handler handler = new CatchingHandler();

    public interface Handler {
        int catching(int divisor, boolean first);
    }

    public static final class CatchingHandler implements Handler {
        public int catching(int divisor, boolean first) {
            try {
                return bridge(divisor, first);
            } catch (ArithmeticException e) {
                exception = e;
                return -1;
            }
        }
    }

    public static int leaf(int divisor, boolean first) {
        if (first) {
            return 84 / divisor;
        }
        return 42 / divisor;
    }

    public static int bridge(int divisor, boolean first) {
        return leaf(divisor, first);
    }

    public static int catching(int divisor, boolean first) {
        try {
            return bridge(divisor, first);
        } catch (ArithmeticException e) {
            exception = e;
            return -1;
        }
    }

    public static int testMixed(int unhandledDivisor, int handledDivisor, boolean first) {
        int result = bridge(unhandledDivisor, first);
        return result + catching(handledDivisor, first);
    }

    public static int testVirtualMixed(int unhandledDivisor, int handledDivisor, boolean first) {
        int result = bridge(unhandledDivisor, first);
        return result + handler.catching(handledDivisor, first);
    }

    private static int callRoot(boolean virtual, int unhandled, int handled, boolean first) {
        return virtual ? testVirtualMixed(unhandled, handled, first)
                       : testMixed(unhandled, handled, first);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (boolean jeandle : new boolean[] {false, true}) {
                for (boolean omitTrace : new boolean[] {false, true}) {
                    for (boolean virtual : new boolean[] {false, true}) {
                        run(jeandle, omitTrace, virtual);
                    }
                }
            }
            return;
        }
        check(Boolean.parseBoolean(args[0]), Boolean.parseBoolean(args[1]));
    }

    private static void run(boolean jeandle, boolean omitTrace, boolean virtual) throws Exception {
        String rootName = virtual ? "testVirtualMixed" : "testMixed";
        List<String> options = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-XX:-TieredCompilation", "-Xbatch", "-XX:CompileThreshold=1000000",
                "-XX:PerMethodTrapLimit=100", "-XX:PerBytecodeTrapLimit=100",
                "-XX:" + (jeandle ? "+" : "-") + "UseJeandleCompiler",
                "-XX:" + (omitTrace ? "+" : "-") + "OmitStackTraceInFastThrow",
                "-XX:CompileCommand=compileonly," + CLASS + "::leaf",
                "-XX:CompileCommand=compileonly," + CLASS + "::" + rootName,
                "-XX:CompileCommand=inline," + CLASS + "::leaf",
                "-XX:CompileCommand=inline," + CLASS + "::bridge",
                "-XX:CompileCommand=inline," + CLASS + "::catching",
                "-XX:CompileCommand=inline," + CLASS + "$CatchingHandler::catching"));
        if (jeandle) {
            options.add("-XX:+JeandlePrintInlineTree");
        }
        options.add(CLASS);
        options.add(Boolean.toString(omitTrace));
        options.add(Boolean.toString(virtual));
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(options));
        output.shouldHaveExitValue(0);
        output.shouldContain("verified caller chains");
        if (jeandle) {
            output.shouldMatch("(?s)Jeandle inline tree:.*::" + rootName
                    + ".*::catching.*::bridge.*::leaf");
        }
    }

    private static void compile(WhiteBox wb, Method method) {
        wb.deoptimizeMethod(method);
        Asserts.assertTrue(wb.enqueueMethodForCompilation(method, 4));
        Asserts.assertEQ(wb.getMethodCompilationLevel(method), 4, "target must compile");
    }

    private static void check(boolean omitTrace, boolean virtual) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        Method leaf = TestInlineBuiltinThrow.class.getMethod("leaf", int.class, boolean.class);
        Method root = TestInlineBuiltinThrow.class.getMethod(
                virtual ? "testVirtualMixed" : "testMixed", int.class, int.class, boolean.class);
        for (int i = 0; i < 5000; i++) {
            Asserts.assertEQ(callRoot(virtual, 1, 1, true), 168);
            Asserts.assertEQ(callRoot(virtual, 1, 1, false), 84);
        }

        compile(wb, leaf);
        int before = wb.getDeoptCount("div0_check", null);
        try {
            leaf(0, true);
            throw new AssertionError("division by zero must throw");
        } catch (ArithmeticException expected) {
        }
        Asserts.assertGT(wb.getDeoptCount("div0_check", null), before,
                "seed trap must execute in the compiled leaf");
        Asserts.assertGT(wb.getMethodTrapCount(leaf, "div0_check"), 0);

        compile(wb, root);
        exception = null;
        before = wb.getDeoptCount("div0_check", null);
        Asserts.assertEQ(callRoot(virtual, 1, 0, false), 41);
        Asserts.assertNotNull(exception, "inlined caller must catch the exception");
        if (omitTrace) {
            Asserts.assertEQ(wb.getDeoptCount("div0_check", null), before,
                    "a handler in the inline chain must enable fast throw at a fresh BCI");
            Asserts.assertEQ(exception.getStackTrace().length, 0,
                    "fast throw must use the preallocated exception");
            Asserts.assertTrue(wb.isMethodCompiled(root));
            ArithmeticException preallocated = exception;
            Field message = Throwable.class.getDeclaredField("detailMessage");
            message.setAccessible(true);
            message.set(preallocated, "mutated");
            Asserts.assertEQ(preallocated.getMessage(), "mutated");
            for (int i = 0; i < 100; i++) {
                Asserts.assertEQ(callRoot(virtual, 1, 0, false), 41);
                Asserts.assertTrue(exception == preallocated);
                Asserts.assertNull(exception.getMessage());
            }
            Asserts.assertEQ(wb.getDeoptCount("div0_check", null), before);
        } else {
            Asserts.assertGT(wb.getDeoptCount("div0_check", null), before);
            Asserts.assertTrue(exception.getStackTrace().length > 0,
                    "trace-preserving throws must retain the inline frames");
            String[] frames = virtual
                    ? new String[] {"leaf", "bridge", "catching", "testVirtualMixed"}
                    : new String[] {"leaf", "bridge", "catching", "testMixed"};
            StackTraceElement[] trace = exception.getStackTrace();
            Asserts.assertGTE(trace.length, frames.length);
            for (int i = 0; i < frames.length; i++) {
                Asserts.assertEQ(trace[i].getMethodName(), frames[i]);
            }
        }

        before = wb.getDeoptCount("div0_check", null);
        try {
            callRoot(virtual, 0, 1, false);
            throw new AssertionError("unhandled division by zero must escape");
        } catch (ArithmeticException expected) {
            Asserts.assertTrue(expected.getStackTrace().length > 0);
        }
        if (omitTrace) {
            Asserts.assertGT(wb.getDeoptCount("div0_check", null), before,
                    "an unrelated handler must not enable fast throw");
        }
        System.out.println("verified caller chains, omitTrace=" + omitTrace + ", virtual=" + virtual);
    }
}
