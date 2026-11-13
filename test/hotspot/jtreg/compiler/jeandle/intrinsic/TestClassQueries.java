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
 * @summary Class queries and dynamic type checks preserve Java semantics and fold constant mirrors
 * @library /test/lib /
 * @modules java.base/jdk.internal.reflect
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm/timeout=600 -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestClassQueries
 */
package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.reflect.Reflection;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestClassQueries {
    private static final String IDS = "_getModifiers,_getClassAccessFlags,_isInterface,_isArray,"
            + "_isPrimitive,_isHidden,_getSuperclass,_isInstance,_isAssignableFrom,_Class_cast";

    public static void main(String[] args) throws Exception {
        String reference = result(run(null, false, "-Xint"));
        for (String gc : List.of("G1", "Serial")) {
            String dump = Files.createTempDirectory("class-query-ir").toString();
            OutputAnalyzer compiled = run(dump, false, "-XX:+Use" + gc + "GC");
            Asserts.assertEquals(result(compiled), reference, "compiled queries match interpreter");
            for (String method : List.of("getModifiers", "getClassAccessFlags", "isInterface", "isArray",
                    "isPrimitive", "isHidden", "getSuperclass", "isInstance", "isAssignableFrom", "cast")) {
                compiled.shouldMatch("Method `[^\\n]*" + method + "[^\\n]*` is parsed as intrinsic");
            }
            new FileCheck(dump, Wrapper.class.getMethod("queryModifiers", Class.class), false)
                    .checkPattern("call .*@jeandle\\.klass_modifiers");
            new FileCheck(dump, Wrapper.class.getMethod("queryFlags", Class.class), false)
                    .checkPattern("call .*@jeandle\\.klass_access_flags");
            new FileCheck(dump, Wrapper.class.getMethod("queryAssignable", Class.class, Class.class), false)
                    .checkPattern("call .*@jeandle\\.check_klass_subtype");
            new FileCheck(dump, Wrapper.class.getMethod("queryFoldArray"), true).check("ret i32 1");
            new FileCheck(dump, Wrapper.class.getMethod("queryFoldModifiers"), true).check("ret i32 1041");
            new FileCheck(dump, Wrapper.class.getMethod("queryTyped", String.class), true)
                    .checkNotPattern("@jeandle\\.check_instanceof");
            FileCheck typed = new FileCheck(dump, Wrapper.class.getMethod("queryTyped", String.class), true);
            typed.checkPattern("icmp ne ptr addrspace\\(1\\) %0, null");
            typed.checkPattern("ret i32");
        }
        Asserts.assertEquals(result(run(null, true, "-XX:+UseG1GC")), reference,
                "disabled intrinsic preserves fallback semantics");
        System.out.println("Class queries PASSED");
    }

    private static OutputAnalyzer run(String dump, boolean disabled, String mode) throws Exception {
        ArrayList<String> flags = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "-Xms128m", "-Xmx256m", mode));
        if (!mode.equals("-Xint")) {
            flags.addAll(List.of("-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:CompileCommand=compileonly," + Wrapper.class.getName() + "::query*",
                    "-Xlog:jeandle=debug"));
        }
        if (dump != null) {
            flags.add("-XX:+JeandleDumpIR");
            flags.add("-XX:JeandleDumpDirectory=" + dump);
        }
        if (disabled) flags.add("-XX:DisableIntrinsic=" + IDS);
        flags.add(Wrapper.class.getName());
        OutputAnalyzer output = ProcessTools.executeTestJava(flags);
        output.shouldHaveExitValue(0).shouldContain("CLASS-RESULT:");
        return output;
    }

    private static String result(OutputAnalyzer output) {
        return output.firstMatch("CLASS-RESULT:([^\\r\\n]+)", 1);
    }

    interface Face { }
    private static class Parent { }
    private static final class Child extends Parent implements Face { }
    static class HiddenTemplate { }

    public static class Wrapper {
        public static int queryModifiers(Class<?> c) { return c.getModifiers(); }
        public static int queryFlags(Class<?> c) { return Reflection.getClassAccessFlags(c); }
        public static boolean queryInterface(Class<?> c) { return c.isInterface(); }
        public static boolean queryArray(Class<?> c) { return c.isArray(); }
        public static boolean queryPrimitive(Class<?> c) { return c.isPrimitive(); }
        public static boolean queryHidden(Class<?> c) { return c.isHidden(); }
        public static Class<?> querySuper(Class<?> c) { return c.getSuperclass(); }
        public static boolean queryInstance(Class<?> c, Object o) { return c.isInstance(o); }
        public static boolean queryAssignable(Class<?> c, Class<?> other) { return c.isAssignableFrom(other); }
        public static Object queryCast(Class<?> c, Object o) { return c.cast(o); }
        public static boolean queryFoldArray() { return String[][].class.isArray(); }
        public static int queryFoldModifiers() { return int.class.getModifiers(); }
        public static boolean queryTyped(String s) { return CharSequence.class.isInstance(s); }
        public static Class<?> queryFoldSuper() { return String[][].class.getSuperclass(); }
        public static boolean queryPhi(boolean choice) {
            Class<?> c = choice ? String[].class : String[].class;
            return c.isArray();
        }

        public static void main(String[] args) throws Exception {
            // Resolve the static target and constant mirror before -Xcomp
            // compiles their wrappers, so the test exercises the intrinsic.
            Asserts.assertEquals(Reflection.getClassAccessFlags(int.class), 1041);
            Asserts.assertTrue(CharSequence.class.isInterface());
            Class<?> hidden;
            try (var bytes = TestClassQueries.class.getResourceAsStream("TestClassQueries$HiddenTemplate.class")) {
                hidden = MethodHandles.lookup().defineHiddenClass(bytes.readAllBytes(), false).lookupClass();
            }
            byte[] template;
            try (var bytes = TestClassQueries.class.getResourceAsStream("TestClassQueries$HiddenTemplate.class")) {
                template = bytes.readAllBytes();
            }
            Class<?> loaderA = new ClassLoader(null) {
                Class<?> define() { return defineClass(null, template, 0, template.length); }
            }.define();
            Class<?> loaderB = new ClassLoader(null) {
                Class<?> define() { return defineClass(null, template, 0, template.length); }
            }.define();
            Asserts.assertEquals(loaderA.getName(), loaderB.getName());
            Asserts.assertTrue(loaderA != loaderB, "same name has distinct loader identity");
            var constructor = loaderA.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object loadedObject = constructor.newInstance();
            Class<?>[] classes = {boolean.class, byte.class, short.class, char.class, int.class, long.class,
                    float.class, double.class, void.class, Object.class, String.class, Integer.class,
                    Parent.class, Child.class, Face.class, Runnable.class, int[].class, String[].class,
                    Object[].class, String[][].class, int[][].class, Cloneable.class,
                    java.io.Serializable.class, hidden, Array.newInstance(hidden, 0).getClass(),
                    loaderA, loaderB, Array.newInstance(loaderA, 0).getClass(),
                    Array.newInstance(loaderB, 0).getClass()};
            Object[] objects = {null, new Object(), "value", Integer.valueOf(42), new int[2],
                    new String[2], new Child(), new String[1][1], loadedObject};
            long digest = 1;
            for (Class<?> c : classes) {
                digest = mix(digest, queryModifiers(c));
                digest = mix(digest, queryFlags(c));
                digest = mix(digest, queryInterface(c) ? 1 : 0);
                digest = mix(digest, queryArray(c) ? 1 : 0);
                digest = mix(digest, queryPrimitive(c) ? 1 : 0);
                digest = mix(digest, queryHidden(c) ? 1 : 0);
                Class<?> parent = querySuper(c);
                digest = mix(digest, parent == null ? 0 : parent == Object.class ? 1 : parent == Parent.class ? 2 : 3);
                for (Class<?> other : classes) digest = mix(digest, queryAssignable(c, other) ? 1 : 0);
                for (Object object : objects) {
                    boolean instance = queryInstance(c, object);
                    digest = mix(digest, instance ? 1 : 0);
                    try {
                        Object cast = queryCast(c, object);
                        Asserts.assertTrue(cast == object, "cast preserves object identity");
                        Asserts.assertTrue(object == null || instance, "successful cast obeys isInstance");
                        digest = mix(digest, 17);
                    } catch (ClassCastException expected) {
                        Asserts.assertTrue(object != null && !instance, "failed cast has incompatible type");
                        Asserts.assertEquals(expected.getMessage(), "Cannot cast " + object.getClass().getName()
                                + " to " + c.getName(), "cast preserves exception message");
                        digest = mix(digest, 19);
                    }
                }
            }
            for (int i = 0; i < 20; i++) {
                expectNPE(() -> queryModifiers(null));
                expectNPE(() -> queryInterface(null));
                expectNPE(() -> queryArray(null));
                expectNPE(() -> queryPrimitive(null));
                expectNPE(() -> queryHidden(null));
                expectNPE(() -> querySuper(null));
                expectNPE(() -> queryInstance(null, null));
                expectNPE(() -> queryAssignable(Object.class, null));
                expectNPE(() -> queryAssignable(null, null));
                expectNPE(() -> queryCast(null, null));
                Asserts.assertTrue(queryCast(int.class, null) == null, "primitive cast of null");
                Asserts.assertTrue(queryCast(void.class, null) == null, "void cast of null");
            }
            Asserts.assertTrue(queryFoldArray());
            Asserts.assertEquals(queryFoldModifiers(), 1041);
            Asserts.assertTrue(queryTyped("value"));
            Asserts.assertFalse(queryTyped(null));
            Asserts.assertTrue(queryFoldSuper() == Object.class);
            Asserts.assertTrue(queryPhi(true) && queryPhi(false));
            System.gc();
            Asserts.assertTrue(querySuper(String.class) == Object.class, "mirror survives collection");
            System.out.println("CLASS-RESULT:" + digest);
        }

        private static long mix(long value, int input) { return value * 31 + input; }
        private static void expectNPE(Runnable action) {
            try {
                action.run();
                throw new AssertionError("Expected NullPointerException");
            } catch (NullPointerException expected) { }
        }
    }
}
