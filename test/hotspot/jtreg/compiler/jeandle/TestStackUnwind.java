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

/**
 * @test
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:CompileCommand=compileonly,TestStackUnwind::test -Xcomp -XX:-TieredCompilation
 *      -XX:+UseJeandleCompiler TestStackUnwind
 */

import jdk.test.whitebox.WhiteBox;

// Unwind a stack like this:
//    [ Native (WhiteBox.fullGC)                 ]
//    [ Interpreter (TestStackUnwind.triggerFGC) ]
//    [ Jeandle Compiled (TestStackUnwind.test)  ]
//    [ Interpreter (TestStackUnwind.main)       ]
public class TestStackUnwind {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    public static void main(String[] args) {
        test();
    }

    static void triggerFGC(int a, int b, int c, int d, int e, int f, int g, int h, int i) {
        wb.fullGC();
    }

    static void test() {
        // Spilled arguments.
        triggerFGC(1,2,3,4,5,6,7,8,9);
    }
}
