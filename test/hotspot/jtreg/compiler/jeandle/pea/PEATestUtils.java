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

package compiler.jeandle.pea;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

/** Shared exact runner and parser support for Jeandle PEA jtreg tests. */
public final class PEATestUtils {
    private static final int FULL_OPTIMIZATION_LEVEL = 4;
    private static final long COMPILE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final String COMPILED_SENTINEL = "PEA-COMPILED:";
    private static final String RESULT_SENTINEL = "PEA-RESULT:";
    private static final String CONFIGURED_TARGETS_PROPERTY =
            "compiler.jeandle.pea.configuredTargets";
    private static final String LLVM_OPTIONS_PREFIX = "-XX:JeandleLLVMOptions=";
    private static final String PEA_OFF_EXTRA_LLVM_ERROR =
            "PEA-off runs do not accept extra LLVM options";
    private static final Set<String> MANAGED_VM_OPTIONS = Set.of(
            "UnlockDiagnosticVMOptions",
            "UnlockExperimentalVMOptions",
            "WhiteBoxAPI",
            "TieredCompilation",
            "UseJeandleCompiler",
            "JeandleDoPEA",
            "JeandleDumpIR",
            "JeandleDumpDirectory",
            "UseCompressedOops",
            "UseCompressedClassPointers",
            "CICompilerCount",
            "CompileCommand",
            "CompileCommandFile",
            "CompileOnly",
            "Flags",
            "VMOptionsFile",
            "LockingMode",
            "JeandleLLVMOptions");
    private static final Set<String> MANAGED_LLVM_OPTIONS = Set.of(
            "jeandle-pea-iterations",
            "jeandle-pea-analyze-function",
            "jeandle-pea-analyze-only",
            "jeandle-dump-pea-ir-function",
            "jeandle-dump-pea-ir",
            "jeandle-dump-pea-stats",
            "jeandle-trace-pea");

    private static final String[] NO_COMPRESSED_OOPS = {
            "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"};
    private static final String[] WHITEBOX_FLAGS = {
            "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI"};
    private static final Pattern MARKER = Pattern.compile(
            "^;; PEA-DUMP (before|after) iter=(\\d+) function (.*?)"
                    + "(?: transform_idle=(true|false|0|1))?$"
    );
    private static final Pattern STATS = Pattern.compile(
            "^;; PEA stats @(.*): NeverEscapes=(\\d+) PartiallyEscapes=(\\d+)"
                    + " AlwaysEscapes=(\\d+)$"
    );
    private static final Pattern EFFECT = Pattern.compile(
            "^PEA: (\\S+) function=(@(?:\"(?:\\\\[0-9A-Fa-f]{2}|[^\"\\\\])*\""
                    + "|[-A-Za-z$._0-9]+))(?:\\s+(.*))?$"
    );
    private static final Pattern LOCK_REPLAY = Pattern.compile(
            "^PEA: LockReplay function=(@(?:\"(?:\\\\[0-9A-Fa-f]{2}|[^\"\\\\])*\""
                    + "|[-A-Za-z$._0-9]+)) logical_escape=([0-9]+) batch=([0-9]+)"
                    + " source=([0-9]+) receiver_vo=([0-9]+)"
                    + " depth=([0-9]+) ordinal=([0-9]+)$"
    );

    private PEATestUtils() {}

    /** Exact identity for one Java method in HotSpot commands and Jeandle IR. */
    public static final class MethodId {
        private final Method method;
        private final String jvmDescriptor;
        private final String dumpStem;
        private final String llvmFunctionName;
        private final String compileCommandPattern;
        private final boolean osr;

        private MethodId(Method method, boolean osr) {
            this.method = Objects.requireNonNull(method);
            this.osr = osr;
            this.jvmDescriptor = MethodType.methodType(
                    method.getReturnType(), method.getParameterTypes()).descriptorString();
            this.dumpStem = method.getDeclaringClass().getName().replace('.', '_')
                    + "_" + method.getName();
            this.llvmFunctionName = (osr ? "__jeandle_osr." : "")
                    + dumpStem + jvmDescriptor;
            this.compileCommandPattern = method.getDeclaringClass().getName() + "::"
                    + method.getName() + jvmDescriptor;
        }

        public static MethodId of(Method method) {
            return new MethodId(method, false);
        }

        public static MethodId osr(Method method) {
            return new MethodId(method, true);
        }

        public Method method() {
            return method;
        }

        public String jvmDescriptor() {
            return jvmDescriptor;
        }

        public String dumpStem() {
            return dumpStem;
        }

        public String llvmFunctionName() {
            return llvmFunctionName;
        }

        public String compileCommandPattern() {
            return compileCommandPattern;
        }

        public boolean isOSR() {
            return osr;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MethodId id
                    && llvmFunctionName.equals(id.llvmFunctionName)
                    && compileCommandPattern.equals(id.compileCommandPattern)
                    && osr == id.osr;
        }

        @Override
        public int hashCode() {
            return Objects.hash(llvmFunctionName, compileCommandPattern, osr);
        }

        @Override
        public String toString() {
            return llvmFunctionName;
        }
    }

    /** Create an exact multi-target run with PEA diagnostics and IR dumps. */
    public static RunBuilder shapeRun(String wrapperFQN, Method... targets) {
        return new RunBuilder(wrapperFQN, true, targets);
    }

    /** Create an exact multi-target run for behavior comparison. */
    public static RunBuilder behaviorRun(String wrapperFQN, Method... targets) {
        return new RunBuilder(wrapperFQN, false, targets);
    }

    /** Builder for one child VM. Every target is explicit and descriptor-qualified. */
    public static final class RunBuilder {
        private static final int MAX_PEA_ITERATIONS = 16;
        private final String wrapperFQN;
        private final boolean shape;
        private final List<MethodId> targets;
        private final List<MethodId> compileOnly;
        private final List<MethodId> inline;
        private final List<MethodId> dontInline;
        private final List<String> extraFlags;
        private final List<String> extraLLVMOptions;
        private boolean peaOn = true;
        private Integer peaIterations;
        private Integer lockingMode;
        private boolean keepDumps;

        private RunBuilder(String wrapperFQN, boolean shape, Method... methods) {
            this.wrapperFQN = Objects.requireNonNull(wrapperFQN);
            this.shape = shape;
            if (methods.length == 0) {
                throw new IllegalArgumentException("At least one explicit target method is required");
            }
            LinkedHashMap<String, MethodId> unique = new LinkedHashMap<>();
            for (Method method : methods) {
                MethodId id = MethodId.of(method);
                if (!method.getDeclaringClass().getName().equals(wrapperFQN)) {
                    throw new IllegalArgumentException("Target " + id
                            + " is not declared by child wrapper " + wrapperFQN);
                }
                if (unique.put(id.llvmFunctionName(), id) != null) {
                    throw new IllegalArgumentException("Duplicate target " + id);
                }
            }
            this.targets = List.copyOf(unique.values());
            this.compileOnly = new ArrayList<>(targets);
            this.inline = new ArrayList<>();
            this.dontInline = new ArrayList<>();
            this.extraFlags = new ArrayList<>();
            this.extraLLVMOptions = new ArrayList<>();
        }

        private RunBuilder(RunBuilder other) {
            this.wrapperFQN = other.wrapperFQN;
            this.shape = other.shape;
            this.targets = other.targets;
            this.compileOnly = new ArrayList<>(other.compileOnly);
            this.inline = new ArrayList<>(other.inline);
            this.dontInline = new ArrayList<>(other.dontInline);
            this.extraFlags = new ArrayList<>(other.extraFlags);
            this.extraLLVMOptions = new ArrayList<>(other.extraLLVMOptions);
            this.peaOn = other.peaOn;
            this.peaIterations = other.peaIterations;
            this.lockingMode = other.lockingMode;
            this.keepDumps = other.keepDumps;
        }

        public RunBuilder compileOnly(Method method) {
            addUnique(compileOnly, MethodId.of(method), "compileonly");
            return this;
        }

        public RunBuilder compileonly(Method method) {
            return compileOnly(method);
        }

        public RunBuilder dontinline(Method method) {
            MethodId id = MethodId.of(method);
            rejectConflictingInlineCommand(id, inline, "inline", "dontinline");
            addUnique(dontInline, id, "dontinline");
            return this;
        }

        public RunBuilder inline(Method method) {
            MethodId id = MethodId.of(method);
            rejectConflictingInlineCommand(id, dontInline, "dontinline", "inline");
            addUnique(inline, id, "inline");
            return this;
        }

        public RunBuilder extraFlags(String... flags) {
            for (String flag : flags) {
                rejectManagedVMFlag(flag);
                extraFlags.add(flag);
            }
            return this;
        }

        public RunBuilder extraLLVMOptions(String... options) {
            if (!peaOn && options.length != 0) {
                throw new IllegalStateException(PEA_OFF_EXTRA_LLVM_ERROR);
            }
            for (String option : options) {
                rejectManagedLLVMOption(option);
            }
            for (String option : options) {
                extraLLVMOptions.add(option);
            }
            return this;
        }

        public RunBuilder peaIterations(int iterations) {
            if (iterations < 1 || iterations > MAX_PEA_ITERATIONS) {
                throw new IllegalArgumentException(
                        "PEA iterations must be in [1, " + MAX_PEA_ITERATIONS + "]");
            }
            if (!peaOn) {
                throw new IllegalStateException("PEA-off runs force zero iterations");
            }
            peaIterations = iterations;
            return this;
        }

        public RunBuilder lockingMode(int mode) {
            if (mode != 1 && mode != 2) {
                throw new IllegalArgumentException("LockingMode must be 1 or 2");
            }
            if (lockingMode != null) {
                throw new IllegalStateException("LockingMode is already configured");
            }
            lockingMode = mode;
            return this;
        }

        public RunBuilder peaOff() {
            if (shape) {
                throw new IllegalStateException("A shape run requires PEA diagnostics");
            }
            if (!extraLLVMOptions.isEmpty()) {
                throw new IllegalStateException(PEA_OFF_EXTRA_LLVM_ERROR);
            }
            peaOn = false;
            peaIterations = null;
            return this;
        }

        public RunBuilder keepDumps() {
            keepDumps = true;
            return this;
        }

        public RunBuilder keepDumps(boolean keep) {
            keepDumps = keep;
            return this;
        }

        public RunResult run() throws Exception {
            Path dumpDir = Files.createTempDirectory("jeandle-pea-dumps-");
            boolean handedOff = false;
            try {
                List<String> command = command(dumpDir);
                ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command);
                OutputAnalyzer output = ProcessTools.executeCommand(pb);
                output.shouldHaveExitValue(0);
                RunResult result = new RunResult(output, command, dumpDir, targets, keepDumps, shape);
                result.assertRequestedMethodsCompiled();
                handedOff = true;
                return result;
            } finally {
                if (!handedOff && !keepDumps) {
                    deleteTree(dumpDir);
                }
            }
        }

        public void runPEAOnOffEquivalent() throws Exception {
            runPEAOnOffEquivalentImpl();
        }

        public PEAOnOffResult runPEAOnOffEquivalentWithCommands() throws Exception {
            return runPEAOnOffEquivalentImpl();
        }

        private PEAOnOffResult runPEAOnOffEquivalentImpl() throws Exception {
            RunBuilder onBuilder = new RunBuilder(this);
            onBuilder.peaOn = true;
            RunBuilder offBuilder = new RunBuilder(onBuilder).peaOff();
            try (RunResult on = onBuilder.run(); RunResult off = offBuilder.run()) {
                String onPayload = exactResultPayload(on.output().getStdout());
                String offPayload = exactResultPayload(off.output().getStdout());
                Asserts.assertEquals(onPayload, offPayload,
                        "PEA-on/off result payload mismatch");
                return new PEAOnOffResult(on.command(), off.command());
            }
        }

        private List<String> command(Path dumpDir) {
            ArrayList<String> command = new ArrayList<>();
            command.addAll(Arrays.asList(WHITEBOX_FLAGS));
            if (lockingMode != null) {
                command.add("-XX:+UnlockExperimentalVMOptions");
                command.add("-XX:LockingMode=" + lockingMode);
            }
            command.add("-Xbatch");
            command.add("-XX:-TieredCompilation");
            command.add("-XX:+UseJeandleCompiler");
            command.add(peaOn ? "-XX:+JeandleDoPEA" : "-XX:-JeandleDoPEA");
            command.add("-Xlog:jeandle=debug");
            command.add(shape ? "-XX:+JeandleDumpIR" : "-XX:-JeandleDumpIR");
            command.add("-XX:JeandleDumpDirectory=" + dumpDir);
            command.addAll(Arrays.asList(NO_COMPRESSED_OOPS));
            command.add("-D" + CONFIGURED_TARGETS_PROPERTY + "="
                    + targets.stream().map(PEATestUtils::configuredTarget)
                            .collect(Collectors.joining(",")));
            if (shape) {
                command.add("-XX:CICompilerCount=1");
            }
            for (MethodId id : compileOnly) {
                command.add("-XX:CompileCommand=compileonly," + id.compileCommandPattern());
            }
            for (MethodId id : inline) {
                command.add("-XX:CompileCommand=inline," + id.compileCommandPattern());
            }
            for (MethodId id : dontInline) {
                command.add("-XX:CompileCommand=dontinline," + id.compileCommandPattern());
            }

            List<String> llvmOptions = new ArrayList<>();
            if (!peaOn) {
                llvmOptions.add("-jeandle-pea-iterations=0");
            } else {
                if (peaIterations != null) {
                    llvmOptions.add("-jeandle-pea-iterations=" + peaIterations);
                }
                if (shape) {
                    llvmOptions.add("-jeandle-trace-pea");
                    llvmOptions.add("-jeandle-dump-pea-stats");
                    for (MethodId id : targets) {
                        llvmOptions.add("-jeandle-pea-analyze-function=" + id.llvmFunctionName());
                        llvmOptions.add("-jeandle-dump-pea-ir-function="
                                + id.llvmFunctionName());
                    }
                }
                llvmOptions.addAll(extraLLVMOptions);
            }
            if (!llvmOptions.isEmpty()) {
                command.add(LLVM_OPTIONS_PREFIX + String.join(" ", llvmOptions));
            }
            command.addAll(extraFlags);
            command.add(wrapperFQN);
            return List.copyOf(command);
        }
    }

    /** Immutable commands from a successful PEA-on/off behavior comparison. */
    public record PEAOnOffResult(List<String> onCommand, List<String> offCommand) {
        public PEAOnOffResult {
            onCommand = List.copyOf(Objects.requireNonNull(onCommand));
            offCommand = List.copyOf(Objects.requireNonNull(offCommand));
        }
    }

    /** Result of one child VM. Closing it removes its unique dump directory. */
    public static final class RunResult implements AutoCloseable {
        private final OutputAnalyzer output;
        private final List<String> command;
        private final Path dumpDir;
        private final List<MethodId> targets;
        private final boolean keepDumps;
        private final boolean shape;
        private PEAReport reports;

        private RunResult(OutputAnalyzer output, List<String> command, Path dumpDir,
                          List<MethodId> targets, boolean keepDumps, boolean shape) {
            this.output = output;
            this.command = command;
            this.dumpDir = dumpDir;
            this.targets = targets;
            this.keepDumps = keepDumps;
            this.shape = shape;
        }

        public OutputAnalyzer output() {
            return output;
        }

        public List<String> command() {
            return command;
        }

        public Path dumpDir() {
            return dumpDir;
        }

        public PEAReport report(Method method) {
            if (!shape) {
                throw new IllegalStateException("Behavior runs do not collect PEA shape reports");
            }
            if (reports == null) {
                reports = PEAReport.parse(output.getStderr(), targets.toArray(MethodId[]::new));
            }
            return reports.report(MethodId.of(method));
        }

        public IRBody frontendIR(Method method) throws IOException {
            return PEATestUtils.frontendIR(dumpDir, MethodId.of(method));
        }

        public IRBody finalIR(Method method) throws IOException {
            return PEATestUtils.finalIR(dumpDir, MethodId.of(method));
        }

        private void assertRequestedMethodsCompiled() {
            List<String> lines = splitLines(output.getStdout());
            long allSentinels = lines.stream().filter(l -> l.startsWith(COMPILED_SENTINEL)).count();
            Asserts.assertEquals(allSentinels, (long) targets.size(),
                    "Expected exactly one compilation sentinel per requested method");
            for (MethodId id : targets) {
                String sentinel = compiledSentinel(id);
                long count = lines.stream().filter(sentinel::equals).count();
                Asserts.assertEquals(count, 1L, "Missing or duplicate sentinel " + sentinel);
            }
        }

        @Override
        public void close() throws IOException {
            if (!keepDumps) {
                deleteTree(dumpDir);
            }
        }
    }

    /** Enqueue each target at level 4 and wait for exact compilation confirmation. */
    public static void enqueueAndAwaitLevel4(Method... methods) throws InterruptedException {
        Objects.requireNonNull(methods);
        if (methods.length == 0) {
            throw new IllegalArgumentException("At least one method is required");
        }
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            Objects.requireNonNull(method);
            if (whiteBox.isMethodCompiled(method)
                    && whiteBox.getMethodCompilationLevel(method)
                            == FULL_OPTIMIZATION_LEVEL) {
                continue;
            }
            if (!whiteBox.isMethodCompilable(method, FULL_OPTIMIZATION_LEVEL)) {
                throw new RuntimeException("Method is not compilable at level 4: "
                        + MethodId.of(method));
            }
            if (!whiteBox.enqueueMethodForCompilation(
                    method, FULL_OPTIMIZATION_LEVEL)) {
                throw new RuntimeException("Level-4 compilation enqueue rejected for "
                        + MethodId.of(method));
            }
            long deadline = System.nanoTime() + COMPILE_TIMEOUT_NANOS;
            while (!whiteBox.isMethodCompiled(method)
                    || whiteBox.getMethodCompilationLevel(method)
                            != FULL_OPTIMIZATION_LEVEL) {
                if (!whiteBox.isMethodCompilable(method, FULL_OPTIMIZATION_LEVEL)) {
                    throw new RuntimeException("Method became not compilable at level 4: "
                            + MethodId.of(method));
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new RuntimeException("Timed out waiting for level-4 compilation of "
                            + MethodId.of(method));
                }
                Thread.sleep(10);
            }
        }
    }

    /** Compile the exact descriptor-qualified targets selected by the parent runner. */
    public static void compileConfiguredTargetsAtLevel4() throws Exception {
        String configured = System.getProperty(CONFIGURED_TARGETS_PROPERTY);
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("No configured PEA targets");
        }
        ArrayList<Method> methods = new ArrayList<>();
        for (String target : configured.split(",", -1)) {
            int separator = target.indexOf('#');
            int descriptor = target.indexOf('(', separator + 1);
            if (separator <= 0 || descriptor <= separator + 1) {
                throw new IllegalStateException("Malformed configured PEA target " + target);
            }
            String className = target.substring(0, separator);
            String methodName = target.substring(separator + 1, descriptor);
            String jvmDescriptor = target.substring(descriptor);
            Class<?> holder = Class.forName(className);
            Method match = null;
            for (Method candidate : holder.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName)
                        && descriptor(candidate).equals(jvmDescriptor)) {
                    if (match != null) {
                        throw new IllegalStateException("Ambiguous configured PEA target " + target);
                    }
                    match = candidate;
                }
            }
            if (match == null) {
                throw new IllegalStateException("Configured PEA target not found " + target);
            }
            methods.add(match);
        }
        enqueueAndAwaitLevel4(methods.toArray(Method[]::new));
        confirmLevel4(methods.toArray(Method[]::new));
    }

    /** Confirm level 4 in the child and publish one exact parent-visible sentinel per method. */
    public static void confirmLevel4(Method... methods) {
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            int level = whiteBox.getMethodCompilationLevel(method);
            if (!whiteBox.isMethodCompiled(method)
                    || level != FULL_OPTIMIZATION_LEVEL) {
                throw new RuntimeException(MethodId.of(method) + " compiled at level " + level
                        + ", expected " + FULL_OPTIMIZATION_LEVEL);
            }
            System.out.println(compiledSentinel(MethodId.of(method)));
        }
    }

    /** Immutable evidence that one compiled target's active frame was marked. */
    public record ActiveFrameDeoptEvidence(
            MethodId target, int frameDepth, int compilationLevel,
            int markedNMethods, boolean frameDeoptimized) {
        public ActiveFrameDeoptEvidence {
            Objects.requireNonNull(target);
            if (frameDepth < 0 || compilationLevel != FULL_OPTIMIZATION_LEVEL
                    || markedNMethods != 1 || !frameDeoptimized) {
                throw new IllegalArgumentException(
                        "Active-frame evidence must describe one marked level-4 nmethod");
            }
        }
    }

    /**
     * Mark one exact level-4 nmethod and prove the requested active frame is
     * deoptimized without globally deoptimizing unrelated frames.
     */
    public static ActiveFrameDeoptEvidence deoptimizeActiveFrame(
            Method target, int frameDepth) {
        Objects.requireNonNull(target);
        if (frameDepth < 0) {
            throw new IllegalArgumentException("Frame depth must be non-negative");
        }
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        int level = whiteBox.getMethodCompilationLevel(target);
        if (!whiteBox.isMethodCompiled(target)
                || level != FULL_OPTIMIZATION_LEVEL) {
            throw new RuntimeException(MethodId.of(target)
                    + " must be compiled at level 4 before active-frame deoptimization"
                    + " (compiled=" + whiteBox.isMethodCompiled(target)
                    + ", level=" + level + ")");
        }
        int markedNMethods = whiteBox.deoptimizeMethod(target);
        if (markedNMethods != 1) {
            throw new RuntimeException("Expected exactly one marked nmethod for "
                    + MethodId.of(target) + ", got " + markedNMethods);
        }
        boolean frameDeoptimized = whiteBox.isFrameDeoptimized(frameDepth);
        if (!frameDeoptimized) {
            throw new RuntimeException("Frame at depth " + frameDepth
                    + " was not deoptimized for " + MethodId.of(target));
        }
        return new ActiveFrameDeoptEvidence(
                MethodId.of(target), frameDepth, level,
                markedNMethods, frameDeoptimized);
    }

    private static String compiledSentinel(MethodId id) {
        return COMPILED_SENTINEL + id.llvmFunctionName() + ":level=4";
    }

    private static String configuredTarget(MethodId id) {
        return id.method().getDeclaringClass().getName() + "#"
                + id.method().getName() + id.jvmDescriptor();
    }

    private static String descriptor(Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                .descriptorString();
    }

    /** Parsed effect line attributed to an exact LLVM function and PEA round. */
    public static final class PEAEffect {
        private final String kind;
        private final String functionName;
        private final int iteration;
        private final String detail;

        private PEAEffect(String kind, String functionName, int iteration, String detail) {
            this.kind = kind;
            this.functionName = functionName;
            this.iteration = iteration;
            this.detail = detail;
        }

        public String kind() {
            return kind;
        }

        public String functionName() {
            return functionName;
        }

        public int iteration() {
            return iteration;
        }

        public String detail() {
            return detail;
        }
    }

    /** Exact typed values from one LockReplay diagnostic. */
    public record PEALockReplay(int logicalEscape, int batch, int source,
                                int receiverVO, int depth, int ordinal) {
        public PEALockReplay {
            if (logicalEscape < 0 || batch < 0 || source < 0
                    || receiverVO < 0 || depth < 0 || ordinal < 0) {
                throw new IllegalArgumentException("LockReplay values must be non-negative");
            }
        }

        public PEALockReplayGroup group() {
            return new PEALockReplayGroup(logicalEscape, batch, source);
        }

        public PEALockReplayPhysicalGroup physicalGroup() {
            return new PEALockReplayPhysicalGroup(batch, source);
        }
    }

    /** One logical consumer's associations within a physical replay batch/path. */
    public record PEALockReplayGroup(int logicalEscape, int batch, int source) {
        public PEALockReplayGroup {
            if (logicalEscape < 0 || batch < 0 || source < 0) {
                throw new IllegalArgumentException("LockReplay group values must be non-negative");
            }
        }
    }

    /** One transform-consumed physical replay batch/path. */
    public record PEALockReplayPhysicalGroup(int batch, int source) {
        public PEALockReplayPhysicalGroup {
            if (batch < 0 || source < 0) {
                throw new IllegalArgumentException(
                        "Physical LockReplay group values must be non-negative");
            }
        }
    }

    private record LockReplayGrouping(
            Map<PEALockReplayGroup, List<PEALockReplay>> logical,
            Map<PEALockReplayPhysicalGroup, List<PEALockReplay>> physical) {}

    /** One complete before/stats/effects/after PEA iteration. */
    public static final class PEARound {
        private final int iteration;
        private final IRBody before;
        private final IRBody after;
        private final int neverEscapes;
        private final int partiallyEscapes;
        private final int alwaysEscapes;
        private final List<PEAEffect> effects;
        private final List<PEALockReplay> lockReplays;
        private final Map<PEALockReplayGroup, List<PEALockReplay>> lockReplayGroups;
        private final Map<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                lockReplayPhysicalGroups;
        private final boolean hasStats;
        private final boolean transformIdle;

        private PEARound(int iteration, IRBody before, IRBody after,
                         int neverEscapes, int partiallyEscapes, int alwaysEscapes,
                         List<PEAEffect> effects, List<PEALockReplay> lockReplays,
                         boolean hasStats, boolean transformIdle) {
            this.iteration = iteration;
            this.before = before;
            this.after = after;
            this.neverEscapes = neverEscapes;
            this.partiallyEscapes = partiallyEscapes;
            this.alwaysEscapes = alwaysEscapes;
            this.effects = List.copyOf(effects);
            this.lockReplays = List.copyOf(lockReplays);
            LockReplayGrouping grouping = groupLockReplays(this.lockReplays, iteration);
            this.lockReplayGroups = grouping.logical();
            this.lockReplayPhysicalGroups = grouping.physical();
            this.hasStats = hasStats;
            this.transformIdle = transformIdle;
        }

        public int iteration() {
            return iteration;
        }

        public IRBody before() {
            return before;
        }

        public IRBody after() {
            return after;
        }

        public int neverEscapes() {
            requireStats();
            return neverEscapes;
        }

        public int partiallyEscapes() {
            requireStats();
            return partiallyEscapes;
        }

        public int alwaysEscapes() {
            requireStats();
            return alwaysEscapes;
        }

        public boolean hasStats() {
            return hasStats;
        }

        public List<PEAEffect> effects() {
            return effects;
        }

        public List<PEALockReplay> lockReplays() {
            return lockReplays;
        }

        public Map<PEALockReplayGroup, List<PEALockReplay>> lockReplayGroups() {
            return lockReplayGroups;
        }

        public Map<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                lockReplayPhysicalGroups() {
            return lockReplayPhysicalGroups;
        }

        public void assertLockReplaySequence(PEALockReplayGroup group,
                                             PEALockReplay... expected) {
            Objects.requireNonNull(group);
            Objects.requireNonNull(expected);
            List<PEALockReplay> actual = lockReplayGroups.getOrDefault(group, List.of());
            Asserts.assertEquals(actual, List.of(expected),
                    "LockReplay sequence for " + group + " in round " + iteration);
        }

        public long distinctLockReplaySourceCount(int logicalEscape) {
            if (logicalEscape < 0) {
                throw new IllegalArgumentException("logical escape must be non-negative");
            }
            return lockReplayGroups.keySet().stream()
                    .filter(group -> group.logicalEscape() == logicalEscape)
                    .map(PEALockReplayGroup::source)
                    .distinct()
                    .count();
        }

        public boolean transformIdle() {
            return transformIdle;
        }

        public long effectCount(String kind, String... detailParts) {
            return matchingEffects(kind, detailParts).size();
        }

        public PEAEffect uniqueEffect(String kind, String... detailParts) {
            List<PEAEffect> matches = matchingEffects(kind, detailParts);
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected exactly one " + kind
                        + " effect matching " + Arrays.toString(detailParts)
                        + " in round " + iteration + ", got " + matches.size());
            }
            return matches.get(0);
        }

        private List<PEAEffect> matchingEffects(String kind, String... detailParts) {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(detailParts);
            for (String detailPart : detailParts) {
                Objects.requireNonNull(detailPart);
            }
            return effects.stream()
                    .filter(effect -> effect.kind().equals(kind))
                    .filter(effect -> Arrays.stream(detailParts)
                            .allMatch(effect.detail()::contains))
                    .collect(Collectors.toUnmodifiableList());
        }

        private void requireStats() {
            if (!hasStats) {
                throw new IllegalStateException("No PEA stats for round " + iteration);
            }
        }

        private static LockReplayGrouping groupLockReplays(
                List<PEALockReplay> replays, int iteration) {
            LinkedHashMap<PEALockReplayGroup, List<PEALockReplay>> logical =
                    new LinkedHashMap<>();
            LinkedHashMap<PEALockReplayPhysicalGroup, List<PEALockReplay>> physical =
                    new LinkedHashMap<>();
            LinkedHashMap<Integer, PEALockReplayPhysicalGroup> batchIdentities =
                    new LinkedHashMap<>();
            HashSet<PEALockReplay> associations = new HashSet<>();
            for (PEALockReplay replay : replays) {
                if (!associations.add(replay)) {
                    throw new IllegalArgumentException(
                            "Duplicate LockReplay association in round "
                            + iteration + ": " + replay);
                }
                PEALockReplayPhysicalGroup previousBatch =
                        batchIdentities.putIfAbsent(replay.batch(), replay.physicalGroup());
                if (previousBatch != null && !previousBatch.equals(replay.physicalGroup())) {
                    throw new IllegalArgumentException(
                            "LockReplay batch " + replay.batch()
                            + " has inconsistent physical identity in round " + iteration
                            + ": " + previousBatch + " vs " + replay.physicalGroup());
                }
                logical.computeIfAbsent(replay.group(), ignored -> new ArrayList<>()).add(replay);
                physical.computeIfAbsent(replay.physicalGroup(),
                        ignored -> new ArrayList<>()).add(replay);
            }

            LinkedHashMap<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                    immutablePhysical = new LinkedHashMap<>();
            for (Map.Entry<PEALockReplayPhysicalGroup, List<PEALockReplay>> entry
                    : physical.entrySet()) {
                int currentOrdinal = -1;
                int currentReceiver = -1;
                int currentDepth = -1;
                for (PEALockReplay replay : entry.getValue()) {
                    if (replay.ordinal() == currentOrdinal) {
                        if (replay.receiverVO() == currentReceiver
                                && replay.depth() == currentDepth) {
                            continue;
                        }
                        throw new IllegalArgumentException(
                                "Conflicting LockReplay aliases for physical ordinal "
                                + replay.ordinal() + " in " + entry.getKey()
                                + " in round " + iteration);
                    }
                    if (replay.ordinal() != currentOrdinal + 1) {
                        throw new IllegalArgumentException(
                                "Non-contiguous physical LockReplay ordinal for "
                                + entry.getKey() + " in round " + iteration + ": expected "
                                + (currentOrdinal + 1) + ", got " + replay.ordinal());
                    }
                    if (replay.depth() <= currentDepth) {
                        throw new IllegalArgumentException(
                                "Non-increasing physical LockReplay depth for "
                                + entry.getKey() + " in round " + iteration);
                    }
                    currentOrdinal = replay.ordinal();
                    currentReceiver = replay.receiverVO();
                    currentDepth = replay.depth();
                }
                immutablePhysical.put(entry.getKey(), List.copyOf(entry.getValue()));
            }

            LinkedHashMap<PEALockReplayGroup, List<PEALockReplay>> immutableLogical =
                    new LinkedHashMap<>();
            for (Map.Entry<PEALockReplayGroup, List<PEALockReplay>> entry
                    : logical.entrySet()) {
                immutableLogical.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new LockReplayGrouping(
                    Collections.unmodifiableMap(immutableLogical),
                    Collections.unmodifiableMap(immutablePhysical));
        }
    }

    /** Exact reports for one or more requested LLVM functions. */
    public static final class PEAReport {
        private final MethodId methodId;
        private final List<PEARound> rounds;
        private final Map<MethodId, PEAReport> reports;

        private PEAReport(MethodId methodId, List<PEARound> rounds) {
            this.methodId = methodId;
            this.rounds = List.copyOf(rounds);
            this.reports = Map.of();
        }

        private PEAReport(Map<MethodId, PEAReport> reports) {
            this.methodId = null;
            this.rounds = List.of();
            this.reports = Collections.unmodifiableMap(new LinkedHashMap<>(reports));
        }

        public static PEAReport parse(String stderr, MethodId... methods) {
            if (methods.length == 0) {
                throw new IllegalArgumentException("At least one exact method is required");
            }
            List<String> lines = splitLines(stderr);
            LinkedHashMap<MethodId, PEAReport> parsed = new LinkedHashMap<>();
            for (MethodId method : methods) {
                if (parsed.containsKey(method)) {
                    throw new IllegalArgumentException("Duplicate report target " + method);
                }
                parsed.put(method, parseFunction(lines, method));
            }
            return new PEAReport(parsed);
        }

        public PEAReport report(MethodId method) {
            PEAReport report = reports.get(method);
            if (report == null) {
                throw new IllegalArgumentException("No report requested for " + method);
            }
            return report;
        }

        public MethodId methodId() {
            requireFunctionReport();
            return methodId;
        }

        public int roundCount() {
            requireFunctionReport();
            return rounds.size();
        }

        public List<PEARound> rounds() {
            requireFunctionReport();
            return rounds;
        }

        public void assertConverged() {
            requireFunctionReport();
            PEARound last = rounds.get(rounds.size() - 1);
            if (!last.transformIdle()) {
                throw new IllegalStateException("PEA did not converge for " + methodId
                        + ": final round " + last.iteration()
                        + " is not transform-idle");
            }
        }

        public PEARound round(int iteration) {
            requireFunctionReport();
            if (iteration < 0 || iteration >= rounds.size()) {
                throw new IllegalArgumentException("No PEA round " + iteration + " for " + methodId);
            }
            return rounds.get(iteration);
        }

        public IRBody round0Before() {
            return round(0).before();
        }

        public IRBody finalAfter() {
            requireFunctionReport();
            return rounds.get(rounds.size() - 1).after();
        }

        public List<PEAEffect> effects(String kind) {
            requireFunctionReport();
            return rounds.stream().flatMap(r -> r.effects().stream())
                    .filter(e -> e.kind().equals(kind)).collect(Collectors.toUnmodifiableList());
        }

        private void requireFunctionReport() {
            if (methodId == null) {
                throw new IllegalStateException("Select an exact method report first");
            }
        }

        private static PEAReport parseFunction(List<String> lines, MethodId method) {
            ArrayList<PEARound> rounds = new ArrayList<>();
            RoundBuilder current = null;
            Capture capture = Capture.NONE;
            String function = method.llvmFunctionName();

            for (String line : lines) {
                Matcher marker = MARKER.matcher(line);
                if (marker.matches()) {
                    String markerFunction = marker.group(3);
                    boolean matches = markerFunction.equals(function);
                    if (!matches) {
                        if (current != null && !current.afterSeen) {
                            throw malformed(method, "interleaved marker before after marker");
                        }
                        if (current != null) {
                            rounds.add(current.finish(method));
                            current = null;
                        }
                        capture = Capture.NONE;
                        continue;
                    }

                    int iteration = Integer.parseInt(marker.group(2));
                    if (marker.group(1).equals("before")) {
                        if (current != null) {
                            if (!current.afterSeen) {
                                throw malformed(method, "duplicate or missing after marker for round "
                                        + current.iteration);
                            }
                            rounds.add(current.finish(method));
                        }
                        if (iteration != rounds.size()) {
                            throw malformed(method, "gapped or duplicate before marker: expected round "
                                    + rounds.size() + ", got " + iteration);
                        }
                        current = new RoundBuilder(iteration);
                        capture = Capture.BEFORE;
                    } else {
                        if (marker.group(4) == null) {
                            throw malformed(method,
                                    "after marker lacks transform-idle flag for round " + iteration);
                        }
                        if (current == null || current.iteration != iteration) {
                            throw malformed(method, "after marker without matching before for round "
                                    + iteration);
                        }
                        if (current.afterSeen) {
                            throw malformed(method, "duplicate after marker for round " + iteration);
                        }
                        current.afterSeen = true;
                        current.transformIdle = marker.group(4).equals("true")
                                || marker.group(4).equals("1");
                        capture = Capture.AFTER;
                    }
                    continue;
                }

                Matcher stats = STATS.matcher(line);
                if (stats.matches()) {
                    capture = Capture.NONE;
                    if (!stats.group(1).equals(function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "stats outside an open round");
                    }
                    if (current.statsSeen) {
                        throw malformed(method, "duplicate stats for round " + current.iteration);
                    }
                    current.statsSeen = true;
                    current.never = Integer.parseInt(stats.group(2));
                    current.partial = Integer.parseInt(stats.group(3));
                    current.always = Integer.parseInt(stats.group(4));
                    continue;
                }

                Matcher effect = EFFECT.matcher(line);
                if (line.startsWith("PEA: LockReplay ")) {
                    capture = Capture.NONE;
                    Matcher lockReplay = LOCK_REPLAY.matcher(line);
                    if (!lockReplay.matches()) {
                        throw malformed(method, "malformed LockReplay line: " + line);
                    }
                    String effectFunction = decodeLLVMOperand(lockReplay.group(1));
                    if (!effectFunction.equals(function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "LockReplay outside an open round");
                    }
                    current.lockReplays.add(new PEALockReplay(
                            lockReplayInt(method, "logical_escape", lockReplay.group(2)),
                            lockReplayInt(method, "batch", lockReplay.group(3)),
                            lockReplayInt(method, "source", lockReplay.group(4)),
                            lockReplayInt(method, "receiver_vo", lockReplay.group(5)),
                            lockReplayInt(method, "depth", lockReplay.group(6)),
                            lockReplayInt(method, "ordinal", lockReplay.group(7))));
                    continue;
                }
                if (effect.matches()) {
                    capture = Capture.NONE;
                    String effectFunction = decodeLLVMOperand(effect.group(2));
                    if (!effectFunction.equals(function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "effect outside an open round");
                    }
                    current.effects.add(new PEAEffect(effect.group(1), effectFunction,
                            current.iteration, effect.group(3) == null ? "" : effect.group(3)));
                    continue;
                }

                if (line.startsWith(";; PEA-DUMP ") || line.startsWith(";; PEA stats @")
                        || line.startsWith("PEA: ")) {
                    capture = Capture.NONE;
                    continue;
                }
                if (current != null) {
                    if (capture == Capture.BEFORE) {
                        current.beforeLines.add(line);
                    } else if (capture == Capture.AFTER) {
                        current.afterLines.add(line);
                    }
                }
            }

            if (current != null) {
                if (!current.afterSeen) {
                    throw malformed(method, "missing after marker for round " + current.iteration);
                }
                rounds.add(current.finish(method));
            }
            if (rounds.isEmpty()) {
                throw malformed(method, "no exact PEA rounds found");
            }
            return new PEAReport(method, rounds);
        }
    }

    private enum Capture { NONE, BEFORE, AFTER }

    private static final class RoundBuilder {
        private final int iteration;
        private final List<String> beforeLines = new ArrayList<>();
        private final List<String> afterLines = new ArrayList<>();
        private final List<PEAEffect> effects = new ArrayList<>();
        private final List<PEALockReplay> lockReplays = new ArrayList<>();
        private boolean statsSeen;
        private boolean afterSeen;
        private boolean transformIdle;
        private int never;
        private int partial;
        private int always;

        private RoundBuilder(int iteration) {
            this.iteration = iteration;
        }

        private PEARound finish(MethodId method) {
            if (!afterSeen) {
                throw malformed(method, "missing after marker for round " + iteration);
            }
            // An allocation-free idle transform need not request PEA analysis, so
            // no stats line is emitted for that round.
            if (!statsSeen && (!transformIdle || !effects.isEmpty()
                    || !lockReplays.isEmpty())) {
                throw malformed(method, "missing stats for active round " + iteration);
            }
            return new PEARound(iteration,
                    IRBody.fromModuleLines(beforeLines, method),
                    IRBody.fromModuleLines(afterLines, method),
                    never, partial, always, effects, lockReplays, statsSeen, transformIdle);
        }
    }

    private static int lockReplayInt(MethodId method, String field, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw malformed(method, "LockReplay " + field + " value overflows int: " + value);
        }
    }

    /** HotSpot basic types carried by Jeandle's deoptimization encoding. */
    public enum DeoptBasicType {
        BOOLEAN(4), CHAR(5), FLOAT(6), DOUBLE(7), BYTE(8), SHORT(9),
        INT(10), LONG(11), OBJECT(12), ARRAY(13), VOID(14), ADDRESS(15),
        NARROW_OOP(16), METADATA(17), NARROW_KLASS(18), CONFLICT(19),
        ILLEGAL(99);

        private final int tag;

        DeoptBasicType(int tag) {
            this.tag = tag;
        }

        public int tag() {
            return tag;
        }

        private static DeoptBasicType fromTag(int tag) {
            for (DeoptBasicType type : values()) {
                if (type.tag == tag) {
                    return type;
                }
            }
            throw new IllegalStateException("Unknown deopt basic type " + tag);
        }
    }

    /** Semantic kind of one decoded deoptimization value. */
    public enum DeoptValueKind {
        SCALAR, NULL, MATERIALIZED_OOP, VO_REF
    }

    /** One immutable decoded scalar, oop, null, or virtual-object reference. */
    public record DeoptValue(
            DeoptValueKind kind, DeoptBasicType basicType,
            String operand, int virtualObjectId) {
        public DeoptValue {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(basicType);
            Objects.requireNonNull(operand);
            if (kind == DeoptValueKind.VO_REF && virtualObjectId < 0) {
                throw new IllegalArgumentException("VORef id must be non-negative");
            }
            if (kind != DeoptValueKind.VO_REF && virtualObjectId != -1) {
                throw new IllegalArgumentException("Only VORefs carry a virtual-object id");
            }
        }
    }

    /** Instance versus array reconstruction descriptor. */
    public enum DescriptorKind {
        INSTANCE, ARRAY
    }

    /** One descriptor field or array element, keyed by its byte offset. */
    public record VirtualObjectEntry(
            int offset, DeoptBasicType basicType, DeoptValue value) {
        public VirtualObjectEntry {
            if (offset < 0) {
                throw new IllegalArgumentException("Descriptor offset must be non-negative");
            }
            Objects.requireNonNull(basicType);
            Objects.requireNonNull(value);
        }
    }

    /** One immutable virtual-object descriptor from the root object pool. */
    public static final class VirtualObjectDescriptor {
        private final int id;
        private final DescriptorKind kind;
        private final String klassOperand;
        private final Map<Integer, VirtualObjectEntry> entries;

        private VirtualObjectDescriptor(
                int id, DescriptorKind kind, String klassOperand,
                Map<Integer, VirtualObjectEntry> entries) {
            this.id = id;
            this.kind = Objects.requireNonNull(kind);
            this.klassOperand = Objects.requireNonNull(klassOperand);
            this.entries = immutableLinkedMap(entries);
        }

        public int id() {
            return id;
        }

        public DescriptorKind kind() {
            return kind;
        }

        public String klassOperand() {
            return klassOperand;
        }

        public Map<Integer, VirtualObjectEntry> entries() {
            return entries;
        }

        public Map<Integer, VirtualObjectEntry> fields() {
            if (kind != DescriptorKind.INSTANCE) {
                throw new IllegalStateException("Array descriptor " + id + " has elements");
            }
            return entries;
        }

        public Map<Integer, VirtualObjectEntry> elements() {
            if (kind != DescriptorKind.ARRAY) {
                throw new IllegalStateException("Instance descriptor " + id + " has fields");
            }
            return entries;
        }
    }

    /** One decoded monitor at its lexical depth in a deopt scope. */
    public record DeoptMonitor(
            int depth, boolean eliminated, DeoptValue owner, String lockOperand) {
        public DeoptMonitor {
            if (depth < 0) {
                throw new IllegalArgumentException("Monitor depth must be non-negative");
            }
            Objects.requireNonNull(owner);
            Objects.requireNonNull(lockOperand);
        }
    }

    /** One immutable root or inlined Java scope in a deopt bundle. */
    public record DeoptScope(
            boolean root, String methodOperand, boolean shouldReexecute,
            int bci, int duplicateBCI, Map<Integer, DeoptValue> locals,
            Map<Integer, DeoptValue> stack, List<DeoptMonitor> monitors,
            String origPcOperand) {
        public DeoptScope {
            Objects.requireNonNull(methodOperand);
            locals = immutableLinkedMap(locals);
            stack = immutableLinkedMap(stack);
            monitors = List.copyOf(monitors);
            Objects.requireNonNull(origPcOperand);
        }
    }

    /** One exact typed deopt operand bundle and its root object pool. */
    public static final class DeoptBundle {
        private final List<DeoptScope> scopes;
        private final Map<Integer, VirtualObjectDescriptor> virtualObjects;

        private DeoptBundle(
                List<DeoptScope> scopes,
                Map<Integer, VirtualObjectDescriptor> virtualObjects) {
            this.scopes = List.copyOf(scopes);
            this.virtualObjects = immutableLinkedMap(virtualObjects);
            if (this.scopes.isEmpty() || !this.scopes.get(0).root()) {
                throw new IllegalArgumentException("A deopt bundle requires one root scope");
            }
        }

        public List<DeoptScope> scopes() {
            return scopes;
        }

        public DeoptScope rootScope() {
            return scopes.get(0);
        }

        public List<DeoptScope> inlineScopes() {
            return scopes.subList(1, scopes.size());
        }

        public Map<Integer, VirtualObjectDescriptor> virtualObjects() {
            return virtualObjects;
        }

        public VirtualObjectDescriptor virtualObject(int id) {
            VirtualObjectDescriptor descriptor = virtualObjects.get(id);
            if (descriptor == null) {
                throw new IllegalStateException("No virtual-object descriptor " + id);
            }
            return descriptor;
        }

        public void assertVirtualObjectIds(int... expectedIds) {
            HashSet<Integer> expected = new HashSet<>();
            for (int id : expectedIds) {
                if (id < 0 || !expected.add(id)) {
                    throw new IllegalArgumentException(
                            "Expected virtual-object ids must be unique and non-negative");
                }
            }
            Asserts.assertEquals(virtualObjects.keySet(), expected,
                    "Exact virtual-object id set");
        }

        public void assertVORef(int ownerId, int offset, int targetId) {
            VirtualObjectEntry entry = virtualObject(ownerId).entries().get(offset);
            Asserts.assertNotNull(entry,
                    "Missing descriptor entry owner=" + ownerId + " offset=" + offset);
            Asserts.assertEquals(entry.value().kind(), DeoptValueKind.VO_REF,
                    "Descriptor entry must be a VORef");
            Asserts.assertEquals(entry.value().virtualObjectId(), targetId,
                    "Descriptor VORef target");
        }
    }

    private record DecodedEncoding(
            int index, int valueType, DeoptBasicType basicType) {}

    private record ScopeBuilderResult(DeoptScope scope, int nextOperand) {}

    private static <K, V> Map<K, V> immutableLinkedMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** One exact LLVM function definition with line- and occurrence-aware assertions. */
    public static final class IRBody {
        private static final String LLVM_LABEL_NAME =
                "(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
        private static final Pattern PEA_ALLOCATION = Pattern.compile(
                "@jeandle\\.new_(?:instance|array)(?=\\s*\\()");
        private static final Pattern LOWERED_ALLOCATION = Pattern.compile(
                "@new_(?:instance|array)(?=\\s*\\()");
        private static final Pattern DEOPT_BCI = Pattern.compile(
                "\\\"deopt\\\"\\(i64 0, i32 (-?\\d+), i32 \\1,");
        private static final Pattern BLOCK_LABEL = Pattern.compile(
                "^(" + LLVM_LABEL_NAME + "):(?: ;.*)?$");
        private final MethodId method;
        private final List<String> lines;
        private final String text;

        private IRBody(MethodId method, List<String> lines) {
            this.method = method;
            this.lines = List.copyOf(lines);
            this.text = String.join("\n", lines);
        }

        private static IRBody fromModuleLines(List<String> rawLines, MethodId method) {
            List<String> folded = rawLines.stream().map(PEATestUtils::fold)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
            ArrayList<List<String>> definitions = new ArrayList<>();
            for (int i = 0; i < folded.size(); i++) {
                String line = folded.get(i);
                String defined = definedFunctionName(line);
                if (!method.llvmFunctionName().equals(defined)) {
                    continue;
                }
                ArrayList<String> body = new ArrayList<>();
                int depth = 0;
                boolean opened = false;
                for (int j = i; j < folded.size(); j++) {
                    String bodyLine = folded.get(j);
                    body.add(bodyLine);
                    int delta = braceDelta(bodyLine);
                    if (delta > 0) {
                        opened = true;
                    }
                    depth += delta;
                    if (opened && depth == 0) {
                        break;
                    }
                }
                if (!opened || depth != 0) {
                    throw new IllegalStateException("Unbalanced function definition for " + method);
                }
                definitions.add(body);
            }
            if (definitions.isEmpty()) {
                throw new IllegalStateException("Exact function definition not found: " + method);
            }
            if (definitions.size() != 1) {
                throw new IllegalStateException("Ambiguous exact function definitions for " + method
                        + ": " + definitions.size());
            }
            return new IRBody(method, definitions.get(0));
        }

        public MethodId methodId() {
            return method;
        }

        public List<String> lines() {
            return lines;
        }

        public int peaAllocCount() {
            return (int) lines.stream().filter(l -> PEA_ALLOCATION.matcher(l).find()).count();
        }

        public int loweredAllocCount() {
            return (int) lines.stream().filter(l -> LOWERED_ALLOCATION.matcher(l).find()).count();
        }

        public List<Integer> allocationBCIs() {
            ArrayList<Integer> result = new ArrayList<>();
            for (String line : lines) {
                if (!PEA_ALLOCATION.matcher(line).find()) {
                    continue;
                }
                Matcher matcher = DEOPT_BCI.matcher(line);
                if (!matcher.find()) {
                    throw new AssertionError(method + ": allocation lacks a source BCI: " + line);
                }
                result.add(Integer.parseInt(matcher.group(1)));
            }
            return List.copyOf(result);
        }

        /** Maps each allocation result SSA value to its source BCI. */
        public Map<String, Integer> allocationBCIsByResult() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (String line : lines) {
                if (!PEA_ALLOCATION.matcher(line).find()) {
                    continue;
                }
                int assignment = line.indexOf(" = ");
                if (assignment <= 1 || line.charAt(0) != '%') {
                    throw new AssertionError(method
                            + ": allocation lacks an SSA result: " + line);
                }
                Matcher matcher = DEOPT_BCI.matcher(line);
                if (!matcher.find()) {
                    throw new AssertionError(method
                            + ": allocation lacks a source BCI: " + line);
                }
                String allocationResult = line.substring(0, assignment);
                Integer previous = result.putIfAbsent(
                        allocationResult, Integer.parseInt(matcher.group(1)));
                if (previous != null) {
                    throw new AssertionError(method
                            + ": duplicate allocation SSA result: " + allocationResult);
                }
            }
            return Collections.unmodifiableMap(result);
        }

        /** Parse the bundle on one call selected by exact LLVM callee and occurrence. */
        public DeoptBundle deoptBundleAtCall(String exactCallee, int occurrence) {
            Objects.requireNonNull(exactCallee);
            if (exactCallee.isEmpty() || exactCallee.charAt(0) == '@') {
                throw new IllegalArgumentException(
                        "Exact callee must be a non-empty raw LLVM function name");
            }
            if (occurrence < 0) {
                throw new IllegalArgumentException("Call occurrence must be non-negative");
            }
            ArrayList<String> matches = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String callee = calledFunctionName(lines.get(i));
                if (exactCallee.equals(callee)) {
                    matches.add(instructionStartingAt(i));
                }
            }
            if (occurrence >= matches.size()) {
                throw new IllegalStateException(method + ": no call occurrence " + occurrence
                        + " of exact callee @" + exactCallee);
            }
            return parseDeoptBundle(method, matches.get(occurrence));
        }

        /** Parse the bundle on one Jeandle allocation selected by exact SSA result. */
        public DeoptBundle deoptBundleAtAllocation(String allocationResult) {
            Objects.requireNonNull(allocationResult);
            if (allocationResult.length() < 2 || allocationResult.charAt(0) != '%'
                    || allocationResult.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "Allocation result must be an exact SSA name beginning with '%'");
            }
            ArrayList<String> matches = new ArrayList<>();
            String assignment = allocationResult + " = ";
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.startsWith(assignment)) {
                    continue;
                }
                String callee = calledFunctionName(line);
                if ("jeandle.new_instance".equals(callee)
                        || "jeandle.new_array".equals(callee)) {
                    matches.add(instructionStartingAt(i));
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalStateException(method
                        + ": allocation SSA result not found: " + allocationResult);
            }
            if (matches.size() != 1) {
                throw new IllegalStateException(method + ": ambiguous allocation SSA result "
                        + allocationResult + ": " + matches.size());
            }
            return parseDeoptBundle(method, matches.get(0));
        }

        private String instructionStartingAt(int startLine) {
            StringBuilder instruction = new StringBuilder(lines.get(startLine));
            int deopt = instruction.indexOf("\"deopt\"(");
            if (deopt < 0) {
                return instruction.toString();
            }
            for (int i = startLine + 1;
                 matchingDelimiter(instruction.toString(),
                         instruction.indexOf("(", deopt), '(', ')') < 0
                         && i < lines.size();
                 i++) {
                instruction.append(' ').append(lines.get(i));
            }
            return instruction.toString();
        }

        public IRBlock blockContaining(String substring, int occurrence) {
            int position = occurrencePosition(substring, occurrence);
            int containingLine = -1;
            int lineStart = 0;
            for (int i = 0; i < lines.size(); i++) {
                int lineEnd = lineStart + lines.get(i).length();
                if (position < lineEnd) {
                    containingLine = i;
                    break;
                }
                lineStart = lineEnd + 1;
            }
            if (containingLine < 0) {
                throw new IllegalStateException(method + ": occurrence is outside the function");
            }

            int blockStart = containingLine;
            while (blockStart >= 0 && !BLOCK_LABEL.matcher(lines.get(blockStart)).matches()) {
                blockStart--;
            }
            if (blockStart < 0) {
                throw new IllegalStateException(method + ": occurrence " + occurrence + " of '"
                        + fold(substring) + "' is outside a labeled LLVM block");
            }

            int blockEnd = blockStart + 1;
            while (blockEnd < lines.size()
                    && !BLOCK_LABEL.matcher(lines.get(blockEnd)).matches()
                    && !lines.get(blockEnd).equals("}")) {
                blockEnd++;
            }
            return new IRBlock(method, lines.subList(blockStart, blockEnd));
        }

        public int lineCount(String substring) {
            String needle = fold(substring);
            return (int) lines.stream().filter(l -> l.contains(needle)).count();
        }

        public int occurrenceCount(String substring) {
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(needle, from)) >= 0) {
                count++;
                from += needle.length();
            }
            return count;
        }

        public void assertPresent(String substring) {
            Asserts.assertTrue(occurrenceCount(substring) > 0,
                    method + ": expected '" + fold(substring) + "'");
        }

        public void assertAbsent(String substring) {
            Asserts.assertEquals(occurrenceCount(substring), 0,
                    method + ": unexpected '" + fold(substring) + "'");
        }

        public void assertLineCount(String substring, int expected) {
            Asserts.assertEquals(lineCount(substring), expected,
                    method + ": line count for '" + fold(substring) + "'");
        }

        public void assertOccurrenceCount(String substring, int expected) {
            Asserts.assertEquals(occurrenceCount(substring), expected,
                    method + ": occurrence count for '" + fold(substring) + "'");
        }

        public void assertBefore(String first, int firstOccurrence,
                                 String second, int secondOccurrence) {
            int firstAt = occurrencePosition(first, firstOccurrence);
            int secondAt = occurrencePosition(second, secondOccurrence);
            Asserts.assertTrue(firstAt < secondAt, method + ": expected occurrence "
                    + firstOccurrence + " of '" + fold(first) + "' before occurrence "
                    + secondOccurrence + " of '" + fold(second) + "'");
        }

        public void assertBetween(String lower, int lowerOccurrence,
                                  String pattern, int patternOccurrence,
                                  String upper, int upperOccurrence) {
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int patternAt = occurrencePosition(pattern, patternOccurrence);
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= patternAt && patternAt < upperAt,
                    method + ": occurrence " + patternOccurrence + " of '" + fold(pattern)
                            + "' is outside the requested interval");
        }

        public void assertAbsentBetween(String lower, int lowerOccurrence,
                                        String pattern, String upper, int upperOccurrence) {
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= upperAt, method + ": invalid interval");
            Asserts.assertFalse(text.substring(lowerAt, upperAt).contains(fold(pattern)),
                    method + ": unexpected '" + fold(pattern) + "' in interval");
        }

        private int occurrencePosition(String substring, int occurrence) {
            if (occurrence < 0) {
                throw new IllegalArgumentException("Occurrence index must be non-negative");
            }
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int at = -needle.length();
            for (int i = 0; i <= occurrence; i++) {
                at = text.indexOf(needle, at + needle.length());
                if (at < 0) {
                    throw new IllegalStateException(method + ": no occurrence " + occurrence
                            + " of '" + needle + "'");
                }
            }
            return at;
        }
    }

    private static DeoptBundle parseDeoptBundle(MethodId method, String instruction) {
        int bundleStart = -1;
        int bundleCount = 0;
        for (int from = 0;
             (from = instruction.indexOf("\"deopt\"(", from)) >= 0;
             from += "\"deopt\"(".length()) {
            bundleStart = from;
            bundleCount++;
        }
        if (bundleCount != 1) {
            throw invalidDeopt(method, "expected exactly one deopt operand bundle, got "
                    + bundleCount + ": " + instruction);
        }
        int open = instruction.indexOf('(', bundleStart);
        int close = matchingDelimiter(instruction, open, '(', ')');
        if (close < 0) {
            throw invalidDeopt(method, "unterminated deopt operand bundle: " + instruction);
        }
        List<String> operands = splitTopLevelOperands(
                instruction.substring(open + 1, close), method);
        LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects =
                new LinkedHashMap<>();
        ArrayList<DeoptScope> scopes = new ArrayList<>();

        ScopeBuilderResult root = parseScope(
                method, operands, 0, true, "", virtualObjects);
        scopes.add(root.scope());
        int at = root.nextOperand();
        while (at < operands.size()) {
            DecodedEncoding marker = decodeEncoding(method, operands.get(at));
            if (marker.valueType() != 6 || marker.index() != 0
                    || marker.basicType() != DeoptBasicType.METADATA) {
                throw invalidDeopt(method,
                        "expected an exact MethodType inline-scope marker at operand " + at);
            }
            requireOperands(method, operands, at, 2, "inline-scope method marker");
            String methodOperand = operands.get(at + 1);
            requireI64Operand(method, methodOperand, "inline method");
            ScopeBuilderResult inline = parseScope(
                    method, operands, at + 2, false,
                    methodOperand, virtualObjects);
            scopes.add(inline.scope());
            at = inline.nextOperand();
        }
        validateVirtualObjectReferences(method, scopes, virtualObjects);
        return new DeoptBundle(scopes, virtualObjects);
    }

    private static ScopeBuilderResult parseScope(
            MethodId method, List<String> operands, int start,
            boolean root, String methodOperand,
            LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects) {
        requireOperands(method, operands, start, 3, "scope header");
        long shouldReexecute = parseI64Constant(
                method, operands.get(start), "should_reexecute");
        if (shouldReexecute != 0 && shouldReexecute != 1) {
            throw invalidDeopt(method,
                    "should_reexecute must be the i64 constant 0 or 1");
        }
        int bci = parseI32Constant(method, operands.get(start + 1), "bci");
        int duplicateBCI = parseI32Constant(
                method, operands.get(start + 2), "duplicated bci");
        if (bci != duplicateBCI) {
            throw invalidDeopt(method, "duplicated BCI mismatch: "
                    + bci + " != " + duplicateBCI);
        }
        int at = start + 3;
        if (root) {
            while (at < operands.size()) {
                DecodedEncoding encoding = tryDecodeEncoding(operands.get(at));
                if (encoding == null || encoding.valueType() != 4) {
                    break;
                }
                at = parseVirtualObjectDescriptor(
                        method, operands, at, encoding, virtualObjects);
            }
        }

        LinkedHashMap<Integer, DeoptValue> locals = new LinkedHashMap<>();
        LinkedHashMap<Integer, DeoptValue> stack = new LinkedHashMap<>();
        ArrayList<DeoptMonitor> monitors = new ArrayList<>();
        String origPcOperand = "";
        int phase = 0;
        while (at < operands.size()) {
            DecodedEncoding encoding = decodeEncoding(method, operands.get(at));
            int valueType = encoding.valueType();
            if (valueType == 6) {
                break;
            }
            if (valueType == 4) {
                throw invalidDeopt(method,
                        "virtual-object descriptor appears after root scope values");
            }
            if (valueType == 7) {
                throw invalidDeopt(method,
                        "narrow-oop markers are unsupported with compressed pointers disabled");
            }
            if (valueType == 0 || valueType == 8) {
                phase = requireScopePhase(method, phase, 0, "local");
                requireOperands(method, operands, at, 2, "local value");
                DeoptValue value = valueType == 8
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                putUniqueScopeValue(method, locals, encoding.index(), value, "local");
                at += 2;
            } else if (valueType == 1 || valueType == 9) {
                phase = requireScopePhase(method, phase, 1, "stack");
                requireOperands(method, operands, at, 2, "stack value");
                DeoptValue value = valueType == 9
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                putUniqueScopeValue(method, stack, encoding.index(), value, "stack");
                at += 2;
            } else if (valueType == 3) {
                phase = requireScopePhase(method, phase, 2, "monitor");
                requireOperands(method, operands, at, 3, "monitor value");
                if (encoding.basicType() != DeoptBasicType.OBJECT
                        || encoding.index() < 0 || encoding.index() > 1) {
                    throw invalidDeopt(method,
                            "monitor encoding requires T_OBJECT and kind index 0 or 1");
                }
                boolean eliminated = encoding.index() == 1;
                DeoptValue owner = eliminated
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                monitors.add(new DeoptMonitor(
                        monitors.size(), eliminated, owner, operands.get(at + 2)));
                at += 3;
            } else if (valueType == 5) {
                phase = requireScopePhase(method, phase, 3, "orig-pc");
                if (!root || !origPcOperand.isEmpty()
                        || encoding.index() != 0
                        || encoding.basicType() != DeoptBasicType.ADDRESS) {
                    throw invalidDeopt(method, "malformed or duplicate root orig-pc slot");
                }
                requireOperands(method, operands, at, 2, "orig-pc value");
                origPcOperand = operands.get(at + 1);
                at += 2;
            } else {
                throw invalidDeopt(method,
                        "unsupported deopt value type " + valueType);
            }
        }
        return new ScopeBuilderResult(new DeoptScope(
                root, methodOperand, shouldReexecute == 1,
                bci, duplicateBCI, locals, stack, monitors, origPcOperand), at);
    }

    private static int parseVirtualObjectDescriptor(
            MethodId method, List<String> operands, int at,
            DecodedEncoding header,
            LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects) {
        if (header.index() < 0
                || header.basicType() != DeoptBasicType.OBJECT
                        && header.basicType() != DeoptBasicType.ARRAY) {
            throw invalidDeopt(method,
                    "virtual-object descriptor requires a non-negative id"
                            + " and T_OBJECT or T_ARRAY");
        }
        requireOperands(method, operands, at, 3, "virtual-object descriptor header");
        requireI64Operand(method, operands.get(at + 1), "virtual-object klass");
        int fieldCount = parseI32Constant(
                method, operands.get(at + 2), "virtual-object field count");
        if (fieldCount < 0) {
            throw invalidDeopt(method,
                    "virtual-object field count must be non-negative");
        }
        requireOperands(method, operands, at, 3 + fieldCount * 2,
                "virtual-object descriptor fields");
        LinkedHashMap<Integer, VirtualObjectEntry> entries = new LinkedHashMap<>();
        int fieldAt = at + 3;
        for (int i = 0; i < fieldCount; i++) {
            DecodedEncoding field = decodeEncoding(method, operands.get(fieldAt));
            if (field.index() < 0 || field.valueType() != 0
                    && field.valueType() != 8) {
                throw invalidDeopt(method,
                        "descriptor entry requires LocalType or VORefLocalType");
            }
            DeoptValue value = field.valueType() == 8
                    ? parseVORef(method, field, operands.get(fieldAt + 1))
                    : parseConcreteValue(method, field, operands.get(fieldAt + 1));
            VirtualObjectEntry previous = entries.putIfAbsent(
                    field.index(), new VirtualObjectEntry(
                            field.index(), field.basicType(), value));
            if (previous != null) {
                throw invalidDeopt(method,
                        "duplicate descriptor offset " + field.index()
                                + " for virtual object " + header.index());
            }
            fieldAt += 2;
        }
        DescriptorKind kind = header.basicType() == DeoptBasicType.ARRAY
                ? DescriptorKind.ARRAY : DescriptorKind.INSTANCE;
        VirtualObjectDescriptor descriptor = new VirtualObjectDescriptor(
                header.index(), kind, operands.get(at + 1), entries);
        if (virtualObjects.putIfAbsent(header.index(), descriptor) != null) {
            throw invalidDeopt(method,
                    "duplicate virtual-object id " + header.index());
        }
        return fieldAt;
    }

    private static DeoptValue parseConcreteValue(
            MethodId method, DecodedEncoding encoding, String operand) {
        DeoptBasicType basicType = encoding.basicType();
        if (basicType == DeoptBasicType.OBJECT
                || basicType == DeoptBasicType.ARRAY) {
            if (!operand.startsWith("ptr ")) {
                throw invalidDeopt(method,
                        "oop value must be a typed ptr operand: " + operand);
            }
            DeoptValueKind kind = operand.matches(
                    "ptr(?: addrspace\\(1\\))? null")
                            ? DeoptValueKind.NULL
                            : DeoptValueKind.MATERIALIZED_OOP;
            return new DeoptValue(kind, basicType, operand, -1);
        }
        return new DeoptValue(
                DeoptValueKind.SCALAR, basicType, operand, -1);
    }

    private static DeoptValue parseVORef(
            MethodId method, DecodedEncoding encoding, String operand) {
        if (encoding.basicType() != DeoptBasicType.OBJECT
                || encoding.valueType() != 8 && encoding.valueType() != 9
                        && encoding.valueType() != 3) {
            throw invalidDeopt(method,
                    "VORef requires T_OBJECT and a VORef slot, field, or monitor encoding");
        }
        int id = parseI32Constant(method, operand, "virtual-object reference");
        if (id < 0) {
            throw invalidDeopt(method,
                    "virtual-object reference id must be non-negative");
        }
        return new DeoptValue(
                DeoptValueKind.VO_REF, DeoptBasicType.OBJECT, operand, id);
    }

    private static void validateVirtualObjectReferences(
            MethodId method, List<DeoptScope> scopes,
            Map<Integer, VirtualObjectDescriptor> virtualObjects) {
        for (VirtualObjectDescriptor descriptor : virtualObjects.values()) {
            for (VirtualObjectEntry entry : descriptor.entries().values()) {
                validateVORef(method, entry.value(), virtualObjects);
            }
        }
        for (DeoptScope scope : scopes) {
            for (DeoptValue value : scope.locals().values()) {
                validateVORef(method, value, virtualObjects);
            }
            for (DeoptValue value : scope.stack().values()) {
                validateVORef(method, value, virtualObjects);
            }
            for (DeoptMonitor monitor : scope.monitors()) {
                validateVORef(method, monitor.owner(), virtualObjects);
            }
        }
    }

    private static void validateVORef(
            MethodId method, DeoptValue value,
            Map<Integer, VirtualObjectDescriptor> virtualObjects) {
        if (value.kind() == DeoptValueKind.VO_REF
                && !virtualObjects.containsKey(value.virtualObjectId())) {
            throw invalidDeopt(method,
                    "dangling virtual-object reference " + value.virtualObjectId());
        }
    }

    private static int requireScopePhase(
            MethodId method, int current, int requested, String section) {
        if (requested < current) {
            throw invalidDeopt(method,
                    "out-of-order " + section + " section");
        }
        return requested;
    }

    private static void putUniqueScopeValue(
            MethodId method, Map<Integer, DeoptValue> values,
            int index, DeoptValue value, String section) {
        if (index < 0 || values.putIfAbsent(index, value) != null) {
            throw invalidDeopt(method,
                    "duplicate or negative " + section + " index " + index);
        }
    }

    private static DecodedEncoding decodeEncoding(MethodId method, String operand) {
        try {
            long encoded = parseUnsignedI64Constant(operand);
            long rawIndex = encoded >>> 32;
            if (rawIndex > Integer.MAX_VALUE) {
                throw invalidDeopt(method, "deopt encoding index overflows int");
            }
            int valueType = (int) ((encoded >>> 16) & 0xffff);
            int basicType = (int) (encoded & 0xffff);
            return new DecodedEncoding(
                    (int) rawIndex, valueType, DeoptBasicType.fromTag(basicType));
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    "deopt encoding must be an unsigned i64 constant: " + operand);
        } catch (IllegalStateException e) {
            throw invalidDeopt(method, e.getMessage());
        }
    }

    private static DecodedEncoding tryDecodeEncoding(String operand) {
        try {
            long encoded = parseUnsignedI64Constant(operand);
            long rawIndex = encoded >>> 32;
            int valueType = (int) ((encoded >>> 16) & 0xffff);
            int basicType = (int) (encoded & 0xffff);
            if (rawIndex > Integer.MAX_VALUE) {
                return null;
            }
            return new DecodedEncoding(
                    (int) rawIndex, valueType, DeoptBasicType.fromTag(basicType));
        } catch (NumberFormatException | IllegalStateException notEncoding) {
            return null;
        }
    }

    private static long parseUnsignedI64Constant(String operand) {
        if (!operand.matches("i64 [0-9]+")) {
            throw new NumberFormatException(operand);
        }
        return Long.parseUnsignedLong(operand.substring(4));
    }

    private static long parseI64Constant(
            MethodId method, String operand, String field) {
        try {
            return parseUnsignedI64Constant(operand);
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    field + " must be an unsigned i64 constant: " + operand);
        }
    }

    private static int parseI32Constant(
            MethodId method, String operand, String field) {
        if (!operand.matches("i32 -?[0-9]+")) {
            throw invalidDeopt(method,
                    field + " must be an i32 constant: " + operand);
        }
        try {
            return Integer.parseInt(operand.substring(4));
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    field + " overflows i32: " + operand);
        }
    }

    private static void requireI64Operand(
            MethodId method, String operand, String field) {
        parseI64Constant(method, operand, field);
    }

    private static void requireOperands(
            MethodId method, List<String> operands,
            int at, int count, String detail) {
        if (count < 0 || at < 0 || at > operands.size() - count) {
            throw invalidDeopt(method,
                    "truncated " + detail + " at operand " + at);
        }
    }

    private static List<String> splitTopLevelOperands(
            String text, MethodId method) {
        ArrayList<String> result = new ArrayList<>();
        int start = 0;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == '(') {
                parentheses++;
            } else if (ch == ')') {
                parentheses--;
            } else if (ch == '[') {
                brackets++;
            } else if (ch == ']') {
                brackets--;
            } else if (ch == '{') {
                braces++;
            } else if (ch == '}') {
                braces--;
            } else if (ch == '<') {
                angles++;
            } else if (ch == '>') {
                angles--;
            } else if (ch == ',' && parentheses == 0 && brackets == 0
                    && braces == 0 && angles == 0) {
                addBundleOperand(result, text.substring(start, i), method);
                start = i + 1;
            }
            if (parentheses < 0 || brackets < 0 || braces < 0 || angles < 0) {
                throw invalidDeopt(method, "unbalanced deopt operand: " + text);
            }
        }
        if (quoted || parentheses != 0 || brackets != 0
                || braces != 0 || angles != 0) {
            throw invalidDeopt(method, "unbalanced deopt operand list: " + text);
        }
        addBundleOperand(result, text.substring(start), method);
        return List.copyOf(result);
    }

    private static void addBundleOperand(
            List<String> operands, String operand, MethodId method) {
        String folded = fold(operand);
        if (folded.isEmpty()) {
            throw invalidDeopt(method, "empty deopt operand");
        }
        operands.add(folded);
    }

    private static int matchingDelimiter(
            String text, int open, char opening, char closing) {
        if (open < 0 || open >= text.length() || text.charAt(open) != opening) {
            return -1;
        }
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == opening) {
                depth++;
            } else if (ch == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String calledFunctionName(String line) {
        Matcher operation = Pattern.compile("\\b(?:call|invoke)\\b").matcher(line);
        if (!operation.find()) {
            return null;
        }
        int at = line.indexOf('@', operation.end());
        return at < 0 ? null : parseLLVMOperand(line, at).value;
    }

    private static IllegalStateException invalidDeopt(MethodId method, String detail) {
        return new IllegalStateException(
                "Malformed deopt bundle for " + method + ": " + detail);
    }

    /** One labeled LLVM basic block with occurrence-aware assertions. */
    public static final class IRBlock {
        private final MethodId method;
        private final String text;

        private IRBlock(MethodId method, List<String> lines) {
            this.method = method;
            this.text = String.join("\n", List.copyOf(lines));
        }

        public int occurrenceCount(String substring) {
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(needle, from)) >= 0) {
                count++;
                from += needle.length();
            }
            return count;
        }

        public void assertAbsent(String substring) {
            Asserts.assertEquals(occurrenceCount(substring), 0,
                    method + ": unexpected '" + fold(substring) + "' in block");
        }

        public void assertBefore(String first, int firstOccurrence,
                                 String second, int secondOccurrence) {
            int firstAt = occurrencePosition(first, firstOccurrence);
            int secondAt = occurrencePosition(second, secondOccurrence);
            Asserts.assertTrue(firstAt < secondAt, method + ": expected occurrence "
                    + firstOccurrence + " of '" + fold(first) + "' before occurrence "
                    + secondOccurrence + " of '" + fold(second) + "' in block");
        }

        private int occurrencePosition(String substring, int occurrence) {
            if (occurrence < 0) {
                throw new IllegalArgumentException("Occurrence index must be non-negative");
            }
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int at = -needle.length();
            for (int i = 0; i <= occurrence; i++) {
                at = text.indexOf(needle, at + needle.length());
                if (at < 0) {
                    throw new IllegalStateException(method + ": no occurrence " + occurrence
                            + " of '" + needle + "' in block");
                }
            }
            return at;
        }
    }

    /** Resolve the exact timestamp-paired frontend dump for a method. */
    public static IRBody frontendIR(Path dumpDir, MethodId method) throws IOException {
        return dumpPair(dumpDir, method).frontend;
    }

    /** Resolve the exact timestamp-paired optimized dump for a method. */
    public static IRBody finalIR(Path dumpDir, MethodId method) throws IOException {
        return dumpPair(dumpDir, method).optimized;
    }

    private static DumpPair dumpPair(Path dumpDir, MethodId method) throws IOException {
        if (!Files.isDirectory(dumpDir)) {
            throw new IllegalArgumentException("Dump path is not a directory: " + dumpDir);
        }
        String prefix = method.dumpStem() + "_";
        LinkedHashMap<String, Path[]> byTimestamp = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(dumpDir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".ll")
                        || name.endsWith("_inline_callees.ll")) {
                    return;
                }
                boolean optimized = name.endsWith("_optimized.ll");
                String suffix = optimized ? "_optimized.ll" : ".ll";
                String timestamp = name.substring(prefix.length(), name.length() - suffix.length());
                if (timestamp.isEmpty()) {
                    return;
                }
                Path[] pair = byTimestamp.computeIfAbsent(timestamp, ignored -> new Path[2]);
                int slot = optimized ? 1 : 0;
                if (pair[slot] != null) {
                    throw new IllegalStateException("Duplicate " + suffix + " dump for "
                            + method + " timestamp " + timestamp);
                }
                pair[slot] = path;
            });
        }

        ArrayList<DumpPair> exact = new ArrayList<>();
        for (Map.Entry<String, Path[]> entry : byTimestamp.entrySet()) {
            Path[] paths = entry.getValue();
            if (paths[0] == null || paths[1] == null) {
                continue;
            }
            IRBody frontend;
            IRBody optimized;
            try {
                frontend = IRBody.fromModuleLines(Files.readAllLines(paths[0]), method);
                optimized = IRBody.fromModuleLines(Files.readAllLines(paths[1]), method);
            } catch (IllegalStateException notThisMethod) {
                if (notThisMethod.getMessage().startsWith("Exact function definition not found:")) {
                    continue;
                }
                throw notThisMethod;
            }
            exact.add(new DumpPair(entry.getKey(), frontend, optimized));
        }
        if (exact.isEmpty()) {
            throw new IllegalStateException("No exact timestamp-paired dumps for " + method
                    + " in " + dumpDir);
        }
        if (exact.size() != 1) {
            throw new IllegalStateException("Ambiguous timestamp-paired dumps for " + method
                    + " in " + dumpDir + ": "
                    + exact.stream().map(p -> p.timestamp).collect(Collectors.joining(", ")));
        }
        return exact.get(0);
    }

    private static final class DumpPair {
        private final String timestamp;
        private final IRBody frontend;
        private final IRBody optimized;

        private DumpPair(String timestamp, IRBody frontend, IRBody optimized) {
            this.timestamp = timestamp;
            this.frontend = frontend;
            this.optimized = optimized;
        }
    }

    /** Compare the single stable result payload from PEA-on and PEA-off children. */
    public static void assertPEAOnOffEquivalent(String wrapperFQN, Method... targets)
            throws Exception {
        try (RunResult on = behaviorRun(wrapperFQN, targets).run();
             RunResult off = behaviorRun(wrapperFQN, targets).peaOff().run()) {
            String onPayload = exactResultPayload(on.output().getStdout());
            String offPayload = exactResultPayload(off.output().getStdout());
            Asserts.assertEquals(onPayload, offPayload, "PEA-on/off result payload mismatch");
        }
    }

    private static String exactResultPayload(String stdout) {
        List<String> results = splitLines(stdout).stream()
                .filter(line -> line.startsWith(RESULT_SENTINEL)).collect(Collectors.toList());
        if (results.size() != 1) {
            throw new IllegalStateException("Expected exactly one " + RESULT_SENTINEL
                    + " line, got " + results.size());
        }
        return results.get(0).substring(RESULT_SENTINEL.length());
    }

    private static void rejectManagedVMFlag(String flag) {
        if (flag.startsWith("@")) {
            throw new IllegalArgumentException("Caller may not use an argument file " + flag);
        }
        if (CONFIGURED_TARGETS_PROPERTY.equals(systemPropertyName(flag))) {
            throw new IllegalArgumentException(
                    "Caller may not override configured PEA targets " + flag);
        }
        String optionName = vmOptionName(flag);
        if (optionName != null && MANAGED_VM_OPTIONS.contains(optionName)) {
            throw new IllegalArgumentException("Caller may not override managed VM flag " + flag);
        }
    }

    private static String systemPropertyName(String flag) {
        if (!flag.startsWith("-D") || flag.length() == 2) {
            return null;
        }
        int equals = flag.indexOf('=', 2);
        return equals < 0 ? flag.substring(2) : flag.substring(2, equals);
    }

    private static String vmOptionName(String flag) {
        if (!flag.startsWith("-XX:") || flag.length() == 4) {
            return null;
        }
        String option = flag.substring(4);
        if (option.charAt(0) == '+' || option.charAt(0) == '-') {
            option = option.substring(1);
        }
        int equals = option.indexOf('=');
        if (equals >= 0) {
            option = option.substring(0, equals);
        }
        if (option.endsWith(":")) {
            option = option.substring(0, option.length() - 1);
        }
        return option;
    }

    private static void rejectManagedLLVMOption(String option) {
        String trimmed = option.trim();
        if (trimmed.isEmpty() || trimmed.contains(" ") || trimmed.contains("\t")) {
            throw new IllegalArgumentException("LLVM options must be individual non-empty arguments: "
                    + option);
        }
        String optionName = trimmed.replaceFirst("^-+", "");
        int equals = optionName.indexOf('=');
        if (equals >= 0) {
            optionName = optionName.substring(0, equals);
        }
        if (MANAGED_LLVM_OPTIONS.contains(optionName)) {
            throw new IllegalArgumentException("Caller may not override managed PEA option " + option);
        }
    }

    private static void addUnique(List<MethodId> methods, MethodId method, String command) {
        if (methods.contains(method)) {
            throw new IllegalArgumentException("Duplicate " + command + " method " + method);
        }
        methods.add(method);
    }

    private static void rejectConflictingInlineCommand(
            MethodId method, List<MethodId> conflicting,
            String existingCommand, String requestedCommand) {
        if (conflicting.contains(method)) {
            throw new IllegalArgumentException("Conflicting " + existingCommand + "/"
                    + requestedCommand + " method " + method);
        }
    }

    private static String definedFunctionName(String line) {
        if (!line.startsWith("define ")) {
            return null;
        }
        int at = line.indexOf('@');
        if (at < 0) {
            return null;
        }
        return parseLLVMOperand(line, at).value;
    }

    private static String decodeLLVMOperand(String operand) {
        ParsedOperand parsed = parseLLVMOperand(operand, 0);
        if (parsed.end != operand.length()) {
            throw new IllegalArgumentException("Trailing characters in LLVM operand " + operand);
        }
        return parsed.value;
    }

    private static ParsedOperand parseLLVMOperand(String text, int at) {
        if (at >= text.length() || text.charAt(at) != '@') {
            throw new IllegalArgumentException("Expected LLVM global operand at " + at + ": " + text);
        }
        int index = at + 1;
        if (index < text.length() && text.charAt(index) == '"') {
            index++;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < text.length() && text.charAt(index) != '"') {
                char ch = text.charAt(index++);
                if (ch == '\\') {
                    if (index + 1 >= text.length()
                            || !isHex(text.charAt(index)) || !isHex(text.charAt(index + 1))) {
                        throw new IllegalArgumentException("Malformed LLVM quoted operand: " + text);
                    }
                    bytes.write(Integer.parseInt(text.substring(index, index + 2), 16));
                    index += 2;
                } else {
                    byte[] encoded = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
                    bytes.writeBytes(encoded);
                }
            }
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unterminated LLVM quoted operand: " + text);
            }
            return new ParsedOperand(bytes.toString(StandardCharsets.UTF_8), index + 1);
        }
        int start = index;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '$'
                    || ch == '.' || ch == '_')) {
                break;
            }
            index++;
        }
        if (index == start) {
            throw new IllegalArgumentException("Empty LLVM global operand: " + text);
        }
        return new ParsedOperand(text.substring(start, index), index);
    }

    private static final class ParsedOperand {
        private final String value;
        private final int end;

        private ParsedOperand(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static boolean isHex(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F';
    }

    private static int braceDelta(String line) {
        int delta = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (!quoted && ch == ';') {
                break;
            }
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted && ch == '\\') {
                i = Math.min(i + 2, line.length() - 1);
                continue;
            }
            if (!quoted && ch == '{') {
                delta++;
            } else if (!quoted && ch == '}') {
                delta--;
            }
        }
        return delta;
    }

    private static String fold(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static List<String> splitLines(String text) {
        return Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static IllegalStateException malformed(MethodId method, String detail) {
        return new IllegalStateException("Malformed PEA transcript for " + method + ": " + detail);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

}
