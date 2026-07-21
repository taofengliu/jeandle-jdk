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
                    + "(?: transform_idle=(?:true|false|0|1))?$"
    );
    private static final Pattern STATS = Pattern.compile(
            "^;; PEA stats @(.*): NeverEscapes=(\\d+) PartiallyEscapes=(\\d+)"
                    + " AlwaysEscapes=(\\d+)$"
    );
    private static final Pattern EFFECT = Pattern.compile(
            "^PEA: (\\S+) function=(@(?:\"(?:\\\\[0-9A-Fa-f]{2}|[^\"\\\\])*\""
                    + "|[-A-Za-z$._0-9]+))(?:\\s+(.*))?$"
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
        private final String wrapperFQN;
        private final boolean shape;
        private final List<MethodId> targets;
        private final List<MethodId> compileOnly;
        private final List<MethodId> dontInline = new ArrayList<>();
        private final List<String> extraFlags = new ArrayList<>();
        private final List<String> extraLLVMOptions = new ArrayList<>();
        private boolean peaOn = true;
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
        }

        public RunBuilder compileOnly(Method method) {
            addUnique(compileOnly, MethodId.of(method), "compileonly");
            return this;
        }

        public RunBuilder compileonly(Method method) {
            return compileOnly(method);
        }

        public RunBuilder dontinline(Method method) {
            addUnique(dontInline, MethodId.of(method), "dontinline");
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

        public RunBuilder peaOff() {
            if (shape) {
                throw new IllegalStateException("A shape run requires PEA diagnostics");
            }
            if (!extraLLVMOptions.isEmpty()) {
                throw new IllegalStateException(PEA_OFF_EXTRA_LLVM_ERROR);
            }
            peaOn = false;
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

        private List<String> command(Path dumpDir) {
            ArrayList<String> command = new ArrayList<>();
            command.addAll(Arrays.asList(WHITEBOX_FLAGS));
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
            for (MethodId id : dontInline) {
                command.add("-XX:CompileCommand=dontinline," + id.compileCommandPattern());
            }

            List<String> llvmOptions = new ArrayList<>();
            if (!peaOn) {
                llvmOptions.add("-jeandle-pea-iterations=0");
            } else if (shape) {
                llvmOptions.add("-jeandle-trace-pea");
                llvmOptions.add("-jeandle-dump-pea-stats");
                for (MethodId id : targets) {
                    llvmOptions.add("-jeandle-pea-analyze-function=" + id.llvmFunctionName());
                    llvmOptions.add("-jeandle-dump-pea-ir-function=" + id.llvmFunctionName());
                }
                llvmOptions.addAll(extraLLVMOptions);
            } else {
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

    /** Enqueue each target at level 4, wait for confirmation, then print exact sentinels. */
    public static void enqueueAndAwaitLevel4(Method... methods) throws InterruptedException {
        if (methods.length == 0) {
            throw new IllegalArgumentException("At least one method is required");
        }
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            whiteBox.deoptimizeMethod(method);
            long deadline = System.nanoTime() + COMPILE_TIMEOUT_NANOS;
            while (whiteBox.getMethodCompilationLevel(method) != FULL_OPTIMIZATION_LEVEL) {
                if (!whiteBox.isMethodQueuedForCompilation(method)) {
                    whiteBox.enqueueMethodForCompilation(method, FULL_OPTIMIZATION_LEVEL);
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new RuntimeException("Timed out waiting for level-4 compilation of "
                            + MethodId.of(method));
                }
                Thread.sleep(10);
            }
        }
        confirmLevel4(methods);
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
    }

    /** Confirm level 4 in the child and publish one exact parent-visible sentinel per method. */
    public static void confirmLevel4(Method... methods) {
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            int level = whiteBox.getMethodCompilationLevel(method);
            if (level != FULL_OPTIMIZATION_LEVEL) {
                throw new RuntimeException(MethodId.of(method) + " compiled at level " + level
                        + ", expected " + FULL_OPTIMIZATION_LEVEL);
            }
            System.out.println(compiledSentinel(MethodId.of(method)));
        }
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

    /** One complete before/stats/effects/after PEA iteration. */
    public static final class PEARound {
        private final int iteration;
        private final IRBody before;
        private final IRBody after;
        private final int neverEscapes;
        private final int partiallyEscapes;
        private final int alwaysEscapes;
        private final List<PEAEffect> effects;
        private final boolean hasStats;

        private PEARound(int iteration, IRBody before, IRBody after,
                         int neverEscapes, int partiallyEscapes, int alwaysEscapes,
                         List<PEAEffect> effects, boolean hasStats) {
            this.iteration = iteration;
            this.before = before;
            this.after = after;
            this.neverEscapes = neverEscapes;
            this.partiallyEscapes = partiallyEscapes;
            this.alwaysEscapes = alwaysEscapes;
            this.effects = List.copyOf(effects);
            this.hasStats = hasStats;
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

        private void requireStats() {
            if (!hasStats) {
                throw new IllegalStateException("No PEA stats for round " + iteration);
            }
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
                        if (current == null || current.iteration != iteration) {
                            throw malformed(method, "after marker without matching before for round "
                                    + iteration);
                        }
                        if (current.afterSeen) {
                            throw malformed(method, "duplicate after marker for round " + iteration);
                        }
                        current.afterSeen = true;
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
        private boolean statsSeen;
        private boolean afterSeen;
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
            return new PEARound(iteration,
                    IRBody.fromModuleLines(beforeLines, method),
                    IRBody.fromModuleLines(afterLines, method),
                    never, partial, always, effects, statsSeen);
        }
    }

    /** One exact LLVM function definition with line- and occurrence-aware assertions. */
    public static final class IRBody {
        private static final Pattern PEA_ALLOCATION = Pattern.compile(
                "@jeandle\\.new_(?:instance|array)(?=\\s*\\()");
        private static final Pattern LOWERED_ALLOCATION = Pattern.compile(
                "@new_(?:instance|array)(?=\\s*\\()");
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
