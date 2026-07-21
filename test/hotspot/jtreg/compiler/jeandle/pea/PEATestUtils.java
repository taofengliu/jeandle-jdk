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

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared harness for Jeandle Partial Escape Analysis (PEA) jtreg tests.
 *
 * Centralizes the driver/child-VM boilerplate every PEA test duplicates, and
 * exposes the three oracles the catalog requires (Java behavior, PEA phase,
 * final IR) through reusable helpers:
 *
 *  - {@link #run(String)} builds and launches a child VM (Jeandle+PEA on by
 *    default), giving each run a unique dump directory so dump-file selection
 *    never depends on alphabetical ordering.
 *  - {@link #peaLLVMOptions(Method, String...)} produces the single
 *    {@code -XX:JeandleLLVMOptions=...} argument that turns on the LLVM-side
 *    PEA trace/stats/dump channels. All three write to the child VM's stderr,
 *    so {@link OutputAnalyzer#getStderr()} carries them back to the driver.
 *  - {@link PEABody} scopes structural assertions to one function body in the
 *    optimized dump (count / present / absent / before / between), replacing
 *    brittle whole-file {@code CHECK-NOT}.
 *  - {@link #assertStats}, {@link #assertEffect}, {@link #assertEffectCount},
 *    {@link #assertNoEffect} parse the {@code ;; PEA stats} and {@code PEA:}
 *    lines on stderr.
 *
 * This is a helper, not a test: it carries no {@code @test} tag.
 */
public final class PEATestUtils {

    private PEATestUtils() {}

    // ---- Standard child-VM flags ---------------------------------------

    /** Jeandle PEA tests must run with both compressed-oop modes off. */
    public static final String[] NO_COMPRESSED_OOPS = {
            "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"};

    /** Base compilation flags used by every PEA child VM. */
    public static final String[] BASE_FLAGS = {
            "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
            "-XX:+JeandleDoPEA", "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR"};

    public static final String[] WHITEBOX_FLAGS = {
            "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI"};

    /** PEA-off control: disable the HotSpot flag AND zero the LLVM iteration cap. */
    public static final String PEA_OFF_HOTSPOT = "-XX:-JeandleDoPEA";
    public static final String PEA_OFF_LLVM = "-XX:JeandleLLVMOptions=-jeandle-pea-iterations=0";

    private static final AtomicInteger DUMP_DIR_COUNTER = new AtomicInteger();

    /**
     * The mangled name matching both the LLVM function name and the
     * {@code FileCheck} dump-file prefix for {@code method}.
     */
    public static String mangledFor(Method method) {
        return method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
    }

    /** Fresh unique dump directory under {@code user.dir}, so dump-file selection is unambiguous. */
    public static Path newDumpDir(String label) {
        File base = new File(System.getProperty("user.dir"));
        File dir = new File(base, "pea-dump-" + label + "-" + DUMP_DIR_COUNTER.incrementAndGet());
        if (!dir.mkdirs()) {
            throw new RuntimeException("Could not create dump dir " + dir);
        }
        return dir.toPath();
    }

    /**
     * Build the single {@code -XX:JeandleLLVMOptions=...} value that focuses the
     * LLVM PEA trace/stats/dump on {@code target}. The same mangled filter is
     * used for {@code -jeandle-pea-analyze-only} and {@code -jeandle-dump-pea-ir}
     * so per-effect trace comes only from the target method.
     *
     * @param target   the method under test (drives the mangled filter)
     * @param extraOpt extra LLVM options appended verbatim
     *                 (e.g. {@code -jeandle-pea-iterations=1})
     */
    public static String peaLLVMOptions(Method target, String... extraOpt) {
        String mangled = mangledFor(target);
        StringBuilder sb = new StringBuilder("-XX:JeandleLLVMOptions=");
        sb.append("-jeandle-pea-analyze-only=").append(mangled);
        sb.append(" -jeandle-trace-pea");
        sb.append(" -jeandle-dump-pea-ir=").append(mangled);
        sb.append(" -jeandle-dump-pea-stats");
        for (String e : extraOpt) {
            sb.append(' ').append(e);
        }
        return sb.toString();
    }

    /**
     * Like {@link #peaLLVMOptions(Method, String...)} but filters PEA to every
     * method declared in {@code wrapperClass} (substring match on the mangled
     * class name). Use for one child-VM run that compiles and analyzes several
     * test methods; per-method attribution then comes from the function-named
     * {@code ;; PEA stats} lines and the per-method dump files.
     */
    public static String peaLLVMOptionsClass(Class<?> wrapperClass, String... extraOpt) {
        String filter = wrapperClass.getName().replace('.', '_');
        StringBuilder sb = new StringBuilder("-XX:JeandleLLVMOptions=");
        sb.append("-jeandle-pea-analyze-only=").append(filter);
        sb.append(" -jeandle-trace-pea");
        sb.append(" -jeandle-dump-pea-ir=").append(filter);
        sb.append(" -jeandle-dump-pea-stats");
        for (String e : extraOpt) {
            sb.append(' ').append(e);
        }
        return sb.toString();
    }

    // ---- Child-VM runner -----------------------------------------------

    /**
     * Fluent builder for a PEA child-VM run. Usage:
     * <pre>{@code
     *   OutputAnalyzer out = PEATestUtils.run("compiler.jeandle.pea.TestX$TestWrapper")
     *       .target(TestWrapper.class.getMethod("test"))
     *       .llvmOptions(PEATestUtils.peaLLVMOptions(m))
     *       .dontinline("sink")
     *       .run();
     * }</pre>
     */
    public static Run run(String wrapperFQN) {
        return new Run(wrapperFQN);
    }

    public static final class Run {
        private final String wrapperFQN;
        private Method target;
        private String llvmOptions = "";
        private boolean whiteBox = false;
        private boolean printNMethods = false;
        private boolean peaOn = true; // false => PEA-off control run
        private boolean explicitCompileOnly = false;
        private final List<String> extraCompileCommands = new ArrayList<>();
        private final List<String> extraFlags = new ArrayList<>();
        private Path dumpDir;

        private Run(String wrapperFQN) {
            this.wrapperFQN = wrapperFQN;
            // Eagerly allocate a unique dump dir tied to this run.
            String label = wrapperFQN.substring(wrapperFQN.lastIndexOf('.') + 1).replace('$', '_');
            this.dumpDir = newDumpDir(label);
        }

        public Run target(Method m) { this.target = m; return this; }

        /** Single {@code -XX:JeandleLLVMOptions=...} value (see {@link #peaLLVMOptions}). */
        public Run llvmOptions(String s) { this.llvmOptions = s; return this; }

        public Run whiteBox(boolean b) { this.whiteBox = b; return this; }

        public Run printNMethods(boolean b) { this.printNMethods = b; return this; }

        /**
         * Add a {@code -XX:CompileCommand=compileonly,<wrapper>::<method>}. When any
         * explicit compileonly is added, the default single-method compileonly
         * (derived from {@link #target(Method)}) is suppressed, so multi-method
         * tests compile several methods.
         */
        public Run compileonly(String method) {
            extraCompileCommands.add("-XX:CompileCommand=compileonly," + wrapperFQN + "::" + method);
            explicitCompileOnly = true;
            return this;
        }

        /** Add a {@code -XX:CompileCommand=dontinline,<wrapper>::<method>}. */
        public Run dontinline(String method) {
            extraCompileCommands.add("-XX:CompileCommand=dontinline," + wrapperFQN + "::" + method);
            return this;
        }

        public Run extraFlags(String... f) { extraFlags.addAll(Arrays.asList(f)); return this; }

        /** Switch to a PEA-off control run (disables PEA on both HotSpot and LLVM sides). */
        public Run peaOff() { this.peaOn = false; return this; }

        public Path dumpDir() { return dumpDir; }

        public String wrapperFQN() { return wrapperFQN; }

        /** Build the child-VM command line and execute it; asserts exit code 0. */
        public OutputAnalyzer run() throws Exception {
            String compileOnlyMethod = (target != null) ? target.getName() : "test";
            ArrayList<String> cmd = new ArrayList<>();
            if (whiteBox) {
                cmd.addAll(Arrays.asList(WHITEBOX_FLAGS));
            }
            cmd.addAll(Arrays.asList(BASE_FLAGS));
            if (!peaOn) {
                // Remove the PEA-on HotSpot flag, then force both sides off.
                cmd.remove("-XX:+JeandleDoPEA");
                cmd.add(PEA_OFF_HOTSPOT);
                cmd.add(PEA_OFF_LLVM);
            } else if (!llvmOptions.isEmpty()) {
                cmd.add(llvmOptions);
            }
            cmd.add("-XX:JeandleDumpDirectory=" + dumpDir);
            if (printNMethods) {
                cmd.add("-XX:+PrintNMethods");
            }
            cmd.addAll(Arrays.asList(NO_COMPRESSED_OOPS));
            // Default: compileonly the target method. If the test added explicit
            // compileonly directives (multi-method), honor those instead.
            if (!explicitCompileOnly) {
                cmd.add("-XX:CompileCommand=compileonly," + wrapperFQN + "::" + compileOnlyMethod);
            }
            cmd.addAll(extraCompileCommands);
            cmd.addAll(extraFlags);
            cmd.add(wrapperFQN);

            ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
            OutputAnalyzer out = ProcessTools.executeCommand(pb);
            out.shouldHaveExitValue(0);
            return out;
        }
    }

    // ---- Scoped method-body oracle (final-IR layer) --------------------

    /**
     * The optimized LLVM-IR body of a single function, with assertions scoped
     * to that body only (not the whole dump file). Whitespace is collapsed to a
     * single space per line, matching {@code FileCheck}.
     */
    public static final class PEABody {
        private static final Pattern ALLOC_RE =
                Pattern.compile("@jeandle\\.new_(instance|array)");

        private final List<String> lines;
        private final String name;

        /** Body of the function in a Jeandle IR dump file (optimized or pre-opt). */
        public PEABody(Path dumpDir, Method method, boolean optimized) throws IOException {
            this.name = mangledFor(method);
            this.lines = sliceFunction(loadDumpLines(dumpDir, name, optimized), name);
        }

        /**
         * Body of the function from raw IR lines (e.g. a PEA-DUMP iteration block
         * from stderr). Lines are folded and empty lines dropped, then the
         * function is brace-matched by its mangled name.
         */
        PEABody(List<String> rawLines, Method method) {
            this.name = mangledFor(method);
            List<String> folded = new ArrayList<>();
            for (String l : rawLines) {
                String f = fold(l);
                if (!f.isEmpty()) {
                    folded.add(f);
                }
            }
            this.lines = sliceFunction(folded, name);
        }

        /** All (whitespace-folded) lines of the function body, in order. */
        public List<String> lines() { return lines; }

        public String functionName() { return name; }

        /**
         * Count PEA-target allocation invokes in this body: the
         * {@code @jeandle.new_instance} / {@code @jeandle.new_array} JavaOps the
         * analyzer operates on. The {@code @jeandle.} prefix excludes the runtime
         * {@code @new_instance} routine a retained allocation is lowered to, so
         * this is the precise signal for "how many allocations does PEA see / keep".
         */
        public int allocCount() {
            return (int) lines.stream().filter(l -> ALLOC_RE.matcher(l).find()).count();
        }

        public void assertPresent(String substr) {
            final String needle = fold(substr);
            Asserts.assertTrue(lines.stream().anyMatch(l -> l.contains(needle)),
                    "PEABody[" + name + "]: expected '" + needle + "'");
        }

        public void assertAbsent(String substr) {
            final String needle = fold(substr);
            Asserts.assertFalse(lines.stream().anyMatch(l -> l.contains(needle)),
                    "PEABody[" + name + "]: unexpected '" + needle + "'");
        }

        public void assertCount(String substr, int expected) {
            final String needle = fold(substr);
            long got = lines.stream().filter(l -> l.contains(needle)).count();
            Asserts.assertTrue(got == expected,
                    "PEABody[" + name + "]: expected " + expected + " of '" + needle + "', got " + got);
        }

        /** Assert the first occurrence of {@code a} precedes the first occurrence of {@code b}. */
        public void assertBefore(String a, String b) {
            a = fold(a); b = fold(b);
            int ia = indexOf(a), ib = indexOf(b);
            Asserts.assertTrue(ia >= 0 && ib >= 0 && ia < ib,
                    "PEABody[" + name + "]: expected '" + a + "' before '" + b + "'"
                            + " (ia=" + ia + ", ib=" + ib + ")");
        }

        /** Assert {@code pat} occurs at least once strictly after {@code lo} and before {@code hi}. */
        public void assertBetween(String lo, String pat, String hi) {
            lo = fold(lo); pat = fold(pat); hi = fold(hi);
            int ilo = indexOf(lo), ihi = indexOf(hi);
            Asserts.assertTrue(ilo >= 0 && ihi > ilo,
                    "PEABody[" + name + "]: bad bounds '" + lo + "'.." + hi + "'");
            boolean found = false;
            for (int i = ilo + 1; i < ihi; i++) {
                if (lines.get(i).contains(pat)) { found = true; break; }
            }
            Asserts.assertTrue(found,
                    "PEABody[" + name + "]: '" + pat + "' not between '" + lo + "' and '" + hi + "'");
        }

        /** Assert {@code pat} does NOT occur strictly between {@code lo} and {@code hi}. */
        public void assertAbsentBetween(String lo, String pat, String hi) {
            lo = fold(lo); pat = fold(pat); hi = fold(hi);
            int ilo = indexOf(lo), ihi = indexOf(hi);
            Asserts.assertTrue(ilo >= 0 && ihi > ilo,
                    "PEABody[" + name + "]: bad bounds '" + lo + "'.." + hi + "'");
            for (int i = ilo + 1; i < ihi; i++) {
                Asserts.assertFalse(lines.get(i).contains(pat),
                        "PEABody[" + name + "]: unexpected '" + pat + "' between '" + lo + "' and '" + hi + "'");
            }
        }

        private int indexOf(String substr) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(substr)) return i;
            }
            return -1;
        }
    }

    // ---- PEA-phase oracle (stderr) -------------------------------------

    private static final Pattern STATS_RE = Pattern.compile(
            ";; PEA stats @([^:]*): NeverEscapes=(\\d+) PartiallyEscapes=(\\d+) AlwaysEscapes=(\\d+)");

    /**
     * Assert some iteration's {@code ;; PEA stats @<func>} line reports the given
     * classification. Stats are emitted once per outer-fixpoint iteration: a
     * NeverEscape object shows {@code NeverEscapes=1} on the iteration that
     * analyzes the original method, then {@code NeverEscapes=0} on later
     * iterations (the allocation is already gone). So the oracle is "the object
     * reached this classification in some iteration", i.e. an exact-triple
     * match exists among the per-iteration stat lines for the target method.
     */
    public static void assertStats(OutputAnalyzer out, Method target,
                                   int never, int partial, int always) {
        String mangled = mangledFor(target);
        Matcher m = STATS_RE.matcher(out.getStderr());
        String seen = null;
        while (m.find()) {
            if (!m.group(1).contains(mangled)) {
                continue;
            }
            int n = Integer.parseInt(m.group(2));
            int p = Integer.parseInt(m.group(3));
            int a = Integer.parseInt(m.group(4));
            seen = (seen == null) ? (n + "/" + p + "/" + a) : seen + ", " + n + "/" + p + "/" + a;
            if (n == never && p == partial && a == always) {
                return;
            }
        }
        throw new RuntimeException("No ;; PEA stats line for " + mangled
                + " matching Never=" + never + " Partial=" + partial + " Always=" + always
                + (seen != null ? "; saw: " + seen : ""));
    }

    /** Count {@code PEA: <kind>} trace lines on stderr for the target method. */
    public static int effectCount(OutputAnalyzer out, String kind) {
        Pattern p = Pattern.compile("PEA: " + Pattern.quote(kind) + "\\b");
        int count = 0;
        for (String line : out.getStderr().split("\n")) {
            if (p.matcher(line).find()) count++;
        }
        return count;
    }

    /**
     * Assert at least one {@code PEA: <kind>} effect fired. Note: in a one-run
     * multi-method test this is a GLOBAL check (it does not attribute the effect
     * to a specific method — the per-effect trace line carries no function name).
     * Use it to corroborate "PEA did work"; for per-method attribution use
     * {@link #assertStats} or the {@link #assertNeverEscapes}/{@link #assertAllocRetained}
     * before/after allocation comparison.
     */
    public static void assertEffect(OutputAnalyzer out, String kind) {
        Asserts.assertTrue(effectCount(out, kind) > 0, "Expected PEA effect: " + kind);
    }

    public static void assertEffectCount(OutputAnalyzer out, String kind, int n) {
        int got = effectCount(out, kind);
        Asserts.assertTrue(got == n, "Expected " + n + " PEA:" + kind + " effects, got " + got);
    }

    public static void assertNoEffect(OutputAnalyzer out, String kind) {
        Asserts.assertTrue(effectCount(out, kind) == 0, "Unexpected PEA effect: " + kind);
    }

    /**
     * Lines of the function IR from a PEA-DUMP iteration block on stderr:
     * {@code ;; PEA-DUMP <before|after> iter=N function <name>}. The block ends
     * at the next line starting with {@code ;;} (the next PEA-DUMP marker or the
     * stats line) or {@code PEA:} (the per-effect trace, which sits between the
     * before-IR and the after-marker), so the returned lines are pure IR.
     */
    public static List<String> iterationIR(OutputAnalyzer out, Method target, int iter, boolean before) {
        String mangled = mangledFor(target);
        String marker = ";; PEA-DUMP " + (before ? "before" : "after")
                + " iter=" + iter + " function ";
        List<String> body = new ArrayList<>();
        boolean inBlock = false;
        for (String line : out.getStderr().split("\n")) {
            if (line.startsWith(marker)) {
                inBlock = line.contains(mangled);
                continue;
            }
            if (inBlock) {
                if (line.startsWith(";; ") || line.startsWith("PEA:")) {
                    inBlock = false;
                    continue;
                }
                body.add(line);
            }
        }
        return body;
    }

    /** The function body as it enters PEA round 0 (pre-transform, pre-lowering). */
    public static PEABody bodyBeforePEA(OutputAnalyzer out, Method target) {
        return new PEABody(iterationIR(out, target, 0, true), target);
    }

    /** The function body right after PEA round 0's transform (pre-lowering). */
    public static PEABody bodyAfterPEA(OutputAnalyzer out, Method target) {
        return new PEABody(iterationIR(out, target, 0, false), target);
    }

    /**
     * NeverEscape oracle via the PEA target: the method has at least one
     * {@code @jeandle.new_(instance|array)} before PEA (it really allocates —
     * also guards against a deopt-stub method that never reached PEA), and zero
     * after PEA (the allocation was eliminated).
     */
    public static void assertNeverEscapes(OutputAnalyzer out, Method target) {
        int before = bodyBeforePEA(out, target).allocCount();
        int after = bodyAfterPEA(out, target).allocCount();
        Asserts.assertTrue(before >= 1,
                target.getName() + ": expected >=1 @jeandle.new_(instance|array) before PEA, got " + before);
        Asserts.assertTrue(after == 0,
                target.getName() + ": expected 0 @jeandle.new_(instance|array) after PEA (NeverEscape), got " + after);
    }

    /**
     * Retained-allocation oracle via the PEA target: exactly {@code retained}
     * {@code @jeandle.new_(instance|array)} remain after PEA (OrigAlloc kept),
     * with at least that many before. Use for PartiallyEscapes / AlwaysEscapes /
     * materialized cases.
     */
    public static void assertAllocRetained(OutputAnalyzer out, Method target, int retained) {
        int before = bodyBeforePEA(out, target).allocCount();
        int after = bodyAfterPEA(out, target).allocCount();
        Asserts.assertTrue(before >= retained,
                target.getName() + ": expected >=" + retained
                        + " @jeandle.new_(instance|array) before PEA, got " + before);
        Asserts.assertTrue(after == retained,
                target.getName() + ": expected " + retained
                        + " @jeandle.new_(instance|array) retained after PEA, got " + after);
    }

    // ---- PEA-on / PEA-off behavioral equivalence ----------------------

    /**
     * Run the same wrapper with PEA on and PEA off (no PEA dumps), asserting the
     * observable stdout is identical. Used to prove an optimization is not
     * silently changing behavior. Both runs share the same extra flags.
     */
    public static void assertPEAOnOffEquivalent(String wrapperFQN, String... extraFlags) throws Exception {
        OutputAnalyzer on = run(wrapperFQN).extraFlags(extraFlags).run();
        OutputAnalyzer off = run(wrapperFQN).peaOff().extraFlags(extraFlags).run();
        String onOut = on.getStdout();
        String offOut = off.getStdout();
        Asserts.assertTrue(onOut.equals(offOut),
                "PEA-on/off behavioral mismatch.\n--- PEA-on ---\n" + onOut
                        + "\n--- PEA-off ---\n" + offOut);
    }

    // ---- internals -----------------------------------------------------

    private static String fold(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static List<String> loadDumpLines(Path dumpDir, String mangledPrefix, boolean optimized)
            throws IOException {
        String suffix = optimized ? "_optimized.ll" : ".ll";
        List<Path> matches = Files.list(dumpDir)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(mangledPrefix) && n.endsWith(suffix)
                            && (optimized || !n.endsWith("_optimized.ll"));
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            throw new RuntimeException("No PEA dump file for " + mangledPrefix
                    + "* in " + dumpDir);
        }
        Path dump = matches.get(matches.size() - 1);
        return Files.readAllLines(dump).stream()
                .map(PEATestUtils::fold)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Slice the body of the function whose mangled name contains {@code mangled},
     * from its {@code define} line to the matching closing brace. Uses a brace
     * counter (not "first standalone }") so inline-asm / metadata braces inside
     * the body cannot end the slice early.
     */
    private static List<String> sliceFunction(List<String> folded, String mangled) {
        List<String> body = new ArrayList<>();
        boolean inFunc = false;
        int depth = 0;
        for (String line : folded) {
            if (!inFunc) {
                if (line.contains("define ") && line.contains(mangled)) {
                    inFunc = true;
                    body.add(line);
                    depth += braceDelta(line);
                    if (depth == 0) {
                        break; // one-line "define ... {}" body
                    }
                }
                continue;
            }
            body.add(line);
            depth += braceDelta(line);
            if (depth == 0) {
                break;
            }
        }
        if (body.isEmpty()) {
            throw new RuntimeException("Function body for '" + mangled + "' not found in dump");
        }
        return body;
    }

    /** Net brace delta of a line: +1 per '{', -1 per '}'. */
    private static int braceDelta(String line) {
        int d = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }
}
