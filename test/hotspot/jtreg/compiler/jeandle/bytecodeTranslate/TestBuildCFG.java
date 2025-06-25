/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 * @test TestBuildCFG.java
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestBuildCFG::test0
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestBuildCFG::testSingleBlockLoop
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestBuildCFG::testLoopOnEntry
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestBuildCFG::testSimpleWhileLoop
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestBuildCFG
 */

package compiler.jeandle.bytecodeTranslate;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBuildCFG {
    private static WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        Asserts.assertEquals(test0(), 14995);

        Asserts.assertEquals(testLoopOnEntry(5), 100);

        testCompile("testSingleBlockLoop");

        testCompile("testSimpleWhileLoop");
    }

    static void testCompile(String func, Class<?>... parameterTypes) throws Exception {
        Method method = TestBuildCFG.class.getDeclaredMethod(func, parameterTypes);
        if (!wb.enqueueMethodForCompilation(method, 4)) {
            throw new RuntimeException("Enqueue " + func + " failed");
        }
        while (!wb.isMethodCompiled(method)) {
            Thread.yield();
        }
    }

    static int test0() {
        int a = 0;
        int b = 0;
        while (a++ < 1000) {
            for (int i = 0; i < 10; i++) {
                switch (b) {
                    case 0: {
                        for (int j = 0; j < 100; j++) {
                            b++;
                        }
                        break;
                    }
                    case 500: b += 2; break;
                    default: b += 3; break;
                }
            }
            if (b > 100 && a < 100 || (b != 1000) && b % 5 != 0) {
                b -= 2;
            }
            a++;
        }
        return b;
    }

    static int testLoopOnEntry(int a) {
        while (a < 100) {
            a++;
        }
        return a;
    }

    static void testSingleBlockLoop() {
        for (int i = 0; ; i++);
    }

    static void testSimpleWhileLoop() {
        while (true) {}
    }
}
