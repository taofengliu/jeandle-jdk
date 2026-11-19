/*
 * Copyright (c) 2026, The Jeandle-JDK Authors. All rights reserved.
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

package org.openjdk.bench.jeandle;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Requires a WhiteBox jar on the boot class path, UnlockDiagnosticVMOptions and
 * WhiteBoxAPI. Set builtin.callerHandlers=false for the historical policy,
 * true for C2 and the caller-aware policy. Use software JMH Blackholes.
 */
@Fork(3)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class InlineBuiltinThrow {
    public static volatile ArithmeticException exception;

    public interface KernelAccess {
        int call(int shape, int divisor, boolean first);
    }

    public static class Kernel implements KernelAccess {
        @CompilerControl(CompilerControl.Mode.INLINE)
        public static int leaf(int divisor, boolean first) {
            return first ? 84 / divisor : 42 / divisor;
        }

        @CompilerControl(CompilerControl.Mode.INLINE)
        public static int localLeaf(int divisor, boolean first) {
            try {
                return first ? 84 / divisor : 42 / divisor;
            } catch (ArithmeticException e) {
                exception = e;
                return -1;
            }
        }

        @CompilerControl(CompilerControl.Mode.INLINE)
        public static int bridge(int divisor, boolean first) {
            return leaf(divisor, first);
        }

        @CompilerControl(CompilerControl.Mode.INLINE)
        public static int catching(int divisor, boolean first) {
            try {
                return bridge(divisor, first);
            } catch (ArithmeticException e) {
                exception = e;
                return -1;
            }
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int rootAncestor(int divisor, boolean first) {
            return catching(divisor, first);
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int rootLocal(int divisor, boolean first) {
            return localLeaf(divisor, first);
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int rootUnhandled(int divisor, boolean first) {
            return bridge(divisor, first);
        }

        @Override
        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int call(int shape, int divisor, boolean first) {
            try {
                return switch (shape) {
                    case 0 -> rootAncestor(divisor, first);
                    case 1 -> rootLocal(divisor, first);
                    default -> rootUnhandled(divisor, first);
                };
            } catch (ArithmeticException e) {
                exception = e;
                return -1;
            }
        }
    }

    private static class KernelLoader extends ClassLoader {
        Class<?> freshKernel() throws Exception {
            try (InputStream stream = InlineBuiltinThrow.class.getResourceAsStream("InlineBuiltinThrow$Kernel.class")) {
                byte[] bytes = stream.readAllBytes();
                return defineClass(Kernel.class.getName(), bytes, 0, bytes.length);
            }
        }
    }

    private static final class WB {
        private static final Class<?> TYPE;
        private static final Object INSTANCE;
        static {
            try {
                TYPE = Class.forName("jdk.test.whitebox.WhiteBox");
                INSTANCE = TYPE.getMethod("getWhiteBox").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("WhiteBox boot class path and VM flags are required", e);
            }
        }

        static Object call(String name, Class<?>[] types, Object... args) {
            try {
                return TYPE.getMethod(name, types).invoke(INSTANCE, args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(name, e);
            }
        }

        static void method(String name, Method method) {
            call(name, new Class<?>[] {Executable.class}, method);
        }

        static int traps() {
            return (int) call("getDeoptCount", new Class<?>[] {String.class, String.class},
                    "div0_check", null);
        }

        static boolean compiled(Method method) {
            return (int) call("getMethodCompilationLevel", new Class<?>[] {Executable.class}, method) == 4;
        }

        static void compile(Method method) {
            method("deoptimizeMethod", method);
            require((boolean) call("enqueueMethodForCompilation",
                    new Class<?>[] {Executable.class, int.class}, method, 4), "enqueue failed");
            require(compiled(method), "level-4 compilation required: " + method);
        }

        static boolean flag(String name) {
            return (boolean) call("getBooleanVMFlag", new Class<?>[] {String.class}, name);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static class TrialSupport {
        int shapeId;
        KernelAccess kernel;
        Method root;
        int trapsBefore;
        int result;
        long rootCompileNanos;

        int invoke(int divisor, boolean first) {
            return kernel.call(shapeId, divisor, first);
        }

        void prepare(String shape, boolean fresh) throws Exception {
            shapeId = switch (shape) {
                case "ancestor" -> 0;
                case "local" -> 1;
                case "none" -> 2;
                default -> throw new IllegalArgumentException(shape);
            };
            String rootName = new String[] {"rootAncestor", "rootLocal", "rootUnhandled"}[shapeId];
            Class<?> type = fresh ? new KernelLoader().freshKernel() : Kernel.class;
            Class.forName(InlineBuiltinThrow.class.getName(), true, type.getClassLoader());
            Class.forName(ArithmeticException.class.getName(), true, type.getClassLoader());
            kernel = (KernelAccess) type.getConstructor().newInstance();
            root = type.getMethod(rootName, int.class, boolean.class);
            Method leafMethod = type.getMethod(shapeId == 1 ? "localLeaf" : "leaf", int.class, boolean.class);
            require((int) WB.call("getMethodTrapCount", new Class<?>[] {Executable.class, String.class},
                    leafMethod, "div0_check") == 0, "fresh method trap history required");
            for (int i = 0; i < 5000; i++) {
                require(invoke(1, true) == 84 && invoke(1, false) == 42, "normal result");
            }
            WB.compile(leafMethod);
            int before = WB.traps();
            try {
                leafMethod.invoke(null, 0, true);
                require(shapeId == 1, "seed exception missing");
            } catch (java.lang.reflect.InvocationTargetException e) {
                require(e.getCause() instanceof ArithmeticException, "seed exception type");
            }
            require(WB.traps() > before, "compiled seed trap required");
            long start = System.nanoTime();
            WB.compile(root);
            rootCompileNanos = System.nanoTime() - start;
        }

        void phase(String edge, String detail) {
            System.out.printf("BUILTIN-PHASE %s %s uptime_ms=%d root=%s class_id=%d compile_ns=%d traps=%d%n",
                    edge, detail, ManagementFactory.getRuntimeMXBean().getUptime(),
                    root.getName(), System.identityHashCode(root.getDeclaringClass()), rootCompileNanos, WB.traps());
        }
    }

    @State(Scope.Thread)
    public static class FirstFaultState extends TrialSupport {
        @Param({"ancestor", "local", "none"})
        public String shape;
        int divisor;
        boolean harnessPrepared;
        final Set<Class<?>> kernels = new HashSet<>();

        @Setup(Level.Iteration)
        public void setup() throws Exception {
            prepare(shape, true);
            require(kernels.add(root.getDeclaringClass()), "each shot needs a fresh kernel class");
            divisor = 1;
            InlineBuiltinThrow bench = new InlineBuiltinThrow();
            Method entry = InlineBuiltinThrow.class.getMethod("firstFault", FirstFaultState.class);
            WB.method("deoptimizeMethod", entry);
            WB.method("clearMethodState", entry);
            require(bench.firstFault(this) == 42, "wrapper warmup");
            WB.compile(kernel.getClass().getMethod("call", int.class, int.class, boolean.class));
            WB.compile(entry);
            if (!harnessPrepared) {
                WB.compile(Class.forName("org.openjdk.jmh.infra.Blackhole").getMethod("consume", int.class));
                WB.compile(Class.forName("org.openjdk.jmh.infra.Blackhole").getDeclaredMethod("consumeFull", int.class));
                harnessPrepared = true;
            }
            // A single shot does not naturally heat the timing stub or Blackhole.
            // Prepare these outside timing, just as the target entry is prepared.
            Class<?> harness = Class.forName(
                    "org.openjdk.bench.jeandle.jmh_generated.InlineBuiltinThrow_firstFault_jmhTest");
            boolean found = false;
            for (Method method : harness.getDeclaredMethods()) {
                if (method.getName().equals("firstFault_ss_jmhStub")) {
                    WB.compile(method);
                    found = true;
                }
            }
            require(found, "single-shot timing stub missing");
            divisor = 0;
            exception = null;
            result = 0;
            phase("start", "firstFault shape=" + shape);
            trapsBefore = WB.traps();
        }

        @TearDown(Level.Iteration)
        public void check() {
            boolean fast = WB.flag("OmitStackTraceInFastThrow")
                    && (shapeId == 1 || (shapeId == 0 && Boolean.getBoolean("builtin.callerHandlers")));
            require(result == -1 && exception != null, "measured exception missing");
            int delta = WB.traps() - trapsBefore;
            require(fast ? delta == 0 : delta > 0, "unexpected measured div0 trap count: " + delta);
            require(fast ? exception.getStackTrace().length == 0
                         : exception.getStackTrace().length > 0, "exception trace policy");
            if (fast) {
                require(WB.compiled(root), "fast throw must preserve compiled root");
            }
            phase("end", "firstFault shape=" + shape + " fast=" + fast + " delta=" + delta);
        }
    }

    @State(Scope.Thread)
    public static class SteadyState extends TrialSupport {
        @Param({"0", "64", "1"})
        public int throwEvery;
        int[] divisors;
        int cursor;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            prepare("ancestor", false);
            require(WB.flag("OmitStackTraceInFastThrow"), "steady matrix requires fast throws");
            divisors = new int[1024];
            for (int i = 0; i < divisors.length; i++) {
                divisors[i] = throwEvery > 0 && i % throwEvery == 0 ? 0 : 1;
            }
            if (throwEvery > 0) {
                require(invoke(0, false) == -1, "initial fault");
                WB.compile(root);
                int before = WB.traps();
                require(invoke(0, false) == -1, "steady fault");
                require(WB.traps() == before && WB.compiled(root), "stable fast-throw policy required");
            }
            InlineBuiltinThrow bench = new InlineBuiltinThrow();
            for (int i = 0; i < 5000; i++) {
                bench.steady(this);
            }
            WB.compile(InlineBuiltinThrow.class.getMethod("steady", SteadyState.class));
        }

        @Setup(Level.Iteration)
        public void setupIteration() {
            require(WB.compiled(root), "compiled steady root required");
            phase("start", "steady every=" + throwEvery);
            trapsBefore = WB.traps();
        }

        @TearDown(Level.Iteration)
        public void check() {
            require(WB.traps() == trapsBefore && WB.compiled(root), "steady path deoptimized");
            phase("end", "steady every=" + throwEvery);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 5, batchSize = 1)
    @Measurement(iterations = 20, batchSize = 1)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int firstFault(FirstFaultState state) {
        return state.result = state.kernel.call(state.shapeId, state.divisor, false);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @Warmup(iterations = 5, time = 1)
    @Measurement(iterations = 5, time = 1)
    public int steady(SteadyState state) {
        state.cursor = (state.cursor + 1) & (state.divisors.length - 1);
        return Kernel.rootAncestor(state.divisors[state.cursor], false);
    }
}
