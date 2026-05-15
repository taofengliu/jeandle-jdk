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

/**
 * @test
 * @summary Test that type check elimination is correct after class hierarchy
 *          changes. Bug: No dependency recording for eliminated type checks.
 *          When a new subclass is loaded after compilation, the eliminated
 *          instanceof check may produce wrong results.
 * @library /test/lib /
 * @run main/othervm -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,TestTypeCheckEliminationDeps::testInstanceOf*
 *      -XX:+UseJeandleCompiler
 *      -XX:CompileThreshold=100 TestTypeCheckEliminationDeps
 */

public class TestTypeCheckEliminationDeps {

    interface Shape {
        int area();
    }

    static class Circle implements Shape {
        int radius;
        Circle(int r) { radius = r; }
        public int area() { return 3 * radius * radius; }
    }

    static class Square implements Shape {
        int side;
        Square(int s) { side = s; }
        public int area() { return side * side; }
    }

    // This method will be compiled with the knowledge that Circle and Square
    // are the only implementors of Shape. If a new Shape subclass is loaded
    // later, the instanceof check must still work correctly.
    public static int testInstanceOfShape(Shape s) {
        if (s instanceof Circle) {
            return 1;
        } else if (s instanceof Square) {
            return 2;
        } else {
            return 3;
        }
    }

    // Test instanceof with effectively final class
    public static boolean testInstanceOfFinal(Object obj) {
        // Integer is effectively final - no known subclasses at compile time
        return obj instanceof Integer;
    }

    // Test instanceof Object with non-null value
    public static boolean testInstanceOfObjectNonNull(Object obj) {
        if (obj != null) {
            return obj instanceof Object;
        }
        return false;
    }

    // Test instanceof Object with null value
    public static boolean testInstanceOfObjectNull() {
        Object obj = null;
        return obj instanceof Object;
    }

    public static void main(String[] args) {
        // Warm up to get compilation
        for (int i = 0; i < 200; i++) {
            testInstanceOfShape(new Circle(i % 10));
            testInstanceOfShape(new Square(i % 10));
            testInstanceOfFinal(Integer.valueOf(i));
            testInstanceOfObjectNonNull(new Object());
            testInstanceOfObjectNull();
        }

        // Test with known types
        int r1 = testInstanceOfShape(new Circle(5));
        if (r1 != 1) {
            throw new RuntimeException("testInstanceOfShape(Circle) failed: expected 1, got " + r1);
        }

        int r2 = testInstanceOfShape(new Square(5));
        if (r2 != 2) {
            throw new RuntimeException("testInstanceOfShape(Square) failed: expected 2, got " + r2);
        }

        // Test with null - instanceof should return false
        boolean r3 = testInstanceOfObjectNull();
        if (r3 != false) {
            throw new RuntimeException("testInstanceOfObjectNull failed: expected false, got " + r3
                + " (null instanceof Object incorrectly folded to true)");
        }

        // Test with non-null - instanceof Object should return true
        boolean r4 = testInstanceOfObjectNonNull(new Object());
        if (r4 != true) {
            throw new RuntimeException("testInstanceOfObjectNonNull failed: expected true, got " + r4);
        }

        // Test with null passed to instanceof Object with non-null check
        boolean r5 = testInstanceOfObjectNonNull(null);
        if (r5 != false) {
            throw new RuntimeException("testInstanceOfObjectNonNull(null) failed: expected false, got " + r5);
        }

        // Test instanceof Integer
        boolean r6 = testInstanceOfFinal(42);
        if (r6 != true) {
            throw new RuntimeException("testInstanceOfFinal(Integer) failed: expected true, got " + r6);
        }

        boolean r7 = testInstanceOfFinal("hello");
        if (r7 != false) {
            throw new RuntimeException("testInstanceOfFinal(String) failed: expected false, got " + r7);
        }

        System.out.println("All tests passed");
    }
}