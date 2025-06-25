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
 * @test TestInstanceof.java
 * @run main/othervm -XX:-TieredCompilation -Xcomp
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.TestInstanceof::test
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.TestInstanceof
 */

package compiler.jeandle.bytecodeTranslate;

public class TestInstanceof {
    static class Animal {}

    static class Dog extends Animal {}

    private static boolean test(Animal myDog) {
        return (myDog instanceof Animal);
    }

    public static void main(String[] args) {
        Animal myAnimal = new Animal();
        Animal myDog = new Dog();

        if (!test(myDog)) {
            throw new RuntimeException("Exeption");
        }
    }
}
