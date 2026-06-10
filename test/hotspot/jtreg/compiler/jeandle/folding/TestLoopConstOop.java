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
 * @summary Test that Jeandle does not fold a Stable field through a loop phi
 *          that later merges the constant oop with a non-constant oop.
 * @library /test/lib
 * @modules java.base/jdk.internal.vm.annotation
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *                   -XX:+UnlockExperimentalVMOptions
 *                   -XX:+WhiteBoxAPI
 *                   -Xbatch -XX:-TieredCompilation
 *                   -XX:+UseJeandleCompiler
 *                   -XX:+FoldStableValues
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.folding.TestLoopConstOop::testLoop
 *                   -XX:CompileCommand=dontinline,compiler.jeandle.folding.TestLoopConstOop::testLoop
 *                   compiler.jeandle.folding.TestLoopConstOop
 */

package compiler.jeandle.folding;

import java.lang.reflect.Method;

import jdk.internal.vm.annotation.Stable;
import jdk.test.whitebox.WhiteBox;

public class TestLoopConstOop {
    static final class Holder {
        @Stable final int value;

        Holder(int value) {
            this.value = value;
        }
    }

    static final Holder CONST = new Holder(42);
    static Holder mutableHolder = new Holder(99);

    public static int testLoop(int iterations) {
        Holder h = CONST;
        int sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += h.value;
            h = mutableHolder;
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        int expected = 42 + 99;
        int interpreterResult = testLoop(2);
        if (interpreterResult != expected) {
            throw new RuntimeException("test setup is broken: " + interpreterResult);
        }

        for (int i = 0; i < 50_000; i++) {
            testLoop(2);
        }

        Method testLoop = TestLoopConstOop.class.getDeclaredMethod("testLoop", int.class);
        if (!WhiteBox.getWhiteBox().isMethodCompiled(testLoop)) {
            throw new RuntimeException("testLoop was not compiled by Jeandle");
        }

        int compiledResult = testLoop(2);
        if (compiledResult != expected) {
            throw new RuntimeException("compiled result is " + compiledResult +
                                       ", expected " + expected);
        }
    }
}
