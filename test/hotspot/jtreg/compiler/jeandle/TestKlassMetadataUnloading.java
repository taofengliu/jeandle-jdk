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
 * @test TestKlassMetadataUnloading
 * @summary Verify that Jeandle-compiled nmethods properly track embedded Klass*
 *          metadata so that class unloading does not leave dangling pointers.
 * @requires vm.opt.final.ClassUnloading
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @build TestKlassMetadataUnloading
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestKlassMetadataUnloading
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.whitebox.WhiteBox;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Creates a cross-classloader scenario where a Jeandle-compiled method in
 * one classloader embeds a Klass* from a class in a DIFFERENT classloader.
 * After making the second classloader eligible for GC, verifies that the
 * nmethod is properly unloaded.
 *
 * Bug: Jeandle embeds Klass* as raw integer immediates without registering
 * them through metadata_Relocation, so finalize_oop_references() cannot
 * discover the referenced Klass's class loader.
 *
 * The JVM's record_dependency() skips recording a CLD dependency when
 * the target classloader IS an ancestor (parent) of the requesting
 * classloader, relying on ClassLoader.parent to keep it alive.
 * After compilation, we use Unsafe to null out that parent field.
 *
 * With correct metadata_Relocation (C2), the nmethod detects the dead
 * classloader and unloads.  With Jeandle's bug, the nmethod survives
 * with a dangling Klass* pointer.
 */
public class TestKlassMetadataUnloading {

    public static class Target {
        public int value = 42;
        @Override public String toString() { return "Target(" + value + ")"; }
    }

    public static class Caller {
        public static Object doNew() {
            return new Target();
        }

        public static Target doCheckcast(Object o) {
            return (Target) o;
        }

        public static boolean doInstanceof(Object o) {
            return o instanceof Target;
        }

        public static Object[] doAnewarray() {
            return new Target[1];
        }
    }

    public static class ChildFirstLoader extends URLClassLoader {
        public ChildFirstLoader(String name, URL[] urls, ClassLoader parent) {
            super(name, urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.endsWith("$Caller")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("run")) {
            runTest(args[1]);
            return;
        }

        String testClasses = System.getProperty("test.classes", ".");

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(
            "-Xbootclasspath/a:.",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+WhiteBoxAPI",
            "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "-XX:-TieredCompilation",
            "-XX:+UseJeandleCompiler",
            "-Xcomp",
            "-Xmn8m",
            "-Xlog:class+unload=debug,jit+compilation=debug",
            "-XX:CompileCommand=compileonly,TestKlassMetadataUnloading$Caller::*",
            "TestKlassMetadataUnloading",
            "run",
            testClasses
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.reportDiagnosticSummary();
        output.shouldHaveExitValue(0);
    }

    /**
     * Core test logic.  Delegates most work to a helper so that all local
     * variables holding Target-related references are in a separate stack
     * frame that can be fully popped before GC.
     */
    private static void runTest(String testClassesDir) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        System.out.println(">>> Starting cross-classloader Klass metadata test");

        URL url = new java.io.File(testClassesDir).toURI().toURL();
        System.out.println(">>> Class files URL: " + url);

        URLClassLoader parentLoader = new URLClassLoader(
                "ParentLoader", new URL[]{ url }, null);
        ChildFirstLoader childLoader = new ChildFirstLoader(
                "ChildLoader", new URL[]{ url }, parentLoader);

        String callerName = TestKlassMetadataUnloading.class.getName() + "$Caller";
        String targetName = TestKlassMetadataUnloading.class.getName() + "$Target";

        Class<?> callerClass = Class.forName(callerName, true, childLoader);
        Class<?> targetClass = Class.forName(targetName, true, parentLoader);

        System.out.println(">>> Caller loaded by: " + callerClass.getClassLoader());
        System.out.println(">>> Target loaded by: " + targetClass.getClassLoader());

        if (callerClass.getClassLoader() == targetClass.getClassLoader()) {
            throw new RuntimeException("Caller and Target must be in different classloaders!");
        }

        // Invoke all methods and check compilation in a helper method.
        // The helper's stack frame holds Method objects whose return types
        // reference Target's Class; once the helper returns those references
        // are no longer on any live stack frame.
        java.lang.reflect.Method doNew = callerClass.getMethod("doNew");
        boolean doNewCompiled = invokeAndCheckCompilation(wb, callerClass, targetClass, doNew);

        if (!doNewCompiled) {
            System.out.println(">>> doNew not compiled, test inconclusive");
            return;
        }

        // --- Break childLoader -> parentLoader reference ---
        WeakReference<ClassLoader> parentRef = new WeakReference<>(parentLoader);

        nullParentField(childLoader);
        System.out.println(">>> childLoader.parent nulled via Unsafe");
        System.out.println(">>> childLoader.getParent() = " + childLoader.getParent());

        // Drop ALL references that could keep parentLoader alive.
        // doNew (return type Object) does NOT reference Target's Class,
        // so it's safe to keep.
        targetClass    = null;
        callerClass    = null;
        childLoader.close();
        childLoader    = null;
        parentLoader.close();
        parentLoader   = null;

        System.out.println(">>> All parentLoader references dropped, triggering GC...");

        wb.fullGC();
        System.out.println(">>> First GC done");
        wb.fullGC();
        System.out.println(">>> Second GC done");
        byte[] pressure = new byte[4 * 1024 * 1024];
        pressure[0] = 1;
        wb.fullGC();
        Reference.reachabilityFence(pressure);
        System.out.println(">>> Third GC done");

        if (parentRef.get() != null) {
            System.out.println(">>> WARNING: parentLoader was NOT collected");
            System.out.println(">>> Test inconclusive (parent classloader still reachable)");
            Reference.reachabilityFence(doNew);
            return;
        }
        System.out.println(">>> parentLoader was collected by GC");

        boolean stillCompiled = wb.isMethodCompiled(doNew);
        System.out.println(">>> doNew still compiled after GC: " + stillCompiled);
        Reference.reachabilityFence(doNew);

        if (stillCompiled) {
            throw new RuntimeException(
                "nmethod for Caller.doNew() was NOT unloaded after " +
                "Target's classloader was collected. Embedded Klass* is " +
                "a dangling pointer — missing metadata_Relocation.");
        }

        System.out.println(">>> Test passed: nmethod properly unloaded");
    }

    /**
     * Invokes all Caller methods (triggering compilation) and returns whether
     * doNew was compiled.  All Method objects with Target in their signature
     * (doCheckcast return type) are local to this frame and die on return.
     */
    private static boolean invokeAndCheckCompilation(
            WhiteBox wb, Class<?> callerClass, Class<?> targetClass,
            java.lang.reflect.Method doNew) throws Exception {

        Object targetInst = targetClass.getDeclaredConstructor().newInstance();

        java.lang.reflect.Method doCheckcast  = callerClass.getMethod("doCheckcast", Object.class);
        java.lang.reflect.Method doInstanceof = callerClass.getMethod("doInstanceof", Object.class);
        java.lang.reflect.Method doAnewarray  = callerClass.getMethod("doAnewarray");

        Object  newResult  = doNew.invoke(null);
        Object  castResult = doCheckcast.invoke(null, targetInst);
        boolean ioResult   = (Boolean) doInstanceof.invoke(null, targetInst);
        Object  arrResult  = doAnewarray.invoke(null);

        System.out.println(">>> doNew: " + newResult);
        System.out.println(">>> doCheckcast: " + castResult);
        System.out.println(">>> doInstanceof: " + ioResult);
        System.out.println(">>> doAnewarray: " + arrResult);
        System.out.println(">>> doNew compiled: " + wb.isMethodCompiled(doNew));
        System.out.println(">>> doCheckcast compiled: " + wb.isMethodCompiled(doCheckcast));
        System.out.println(">>> doInstanceof compiled: " + wb.isMethodCompiled(doInstanceof));
        System.out.println(">>> doAnewarray compiled: " + wb.isMethodCompiled(doAnewarray));

        Reference.reachabilityFence(newResult);
        Reference.reachabilityFence(castResult);
        Reference.reachabilityFence(arrResult);
        Reference.reachabilityFence(targetInst);

        return wb.isMethodCompiled(doNew);
    }

    private static void nullParentField(ClassLoader classLoader) throws Exception {
        Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");

        java.lang.reflect.Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafeField.setAccessible(true);
        Object unsafe = theUnsafeField.get(null);

        long offset = (long) unsafeClass.getMethod(
                "objectFieldOffset", Class.class, String.class)
                .invoke(unsafe, java.lang.ClassLoader.class, "parent");

        java.lang.reflect.Method putMethod;
        try {
            putMethod = unsafeClass.getMethod("putReference",
                Object.class, long.class, Object.class);
        } catch (NoSuchMethodException e) {
            putMethod = unsafeClass.getMethod("putObject",
                Object.class, long.class, Object.class);
        }
        putMethod.invoke(unsafe, classLoader, offset, (Object) null);
    }
}
