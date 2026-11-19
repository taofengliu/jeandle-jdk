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
 */
package org.openjdk.bench.jeandle;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleSafepointCoalescing {
    @Param({"17", "-31"})
    public int seed;

    private int sequence;

    @CompilerControl(CompilerControl.Mode.INLINE)
    static int adjust(int value) {
        int i = 0;
        while (i < 3) {
            if (value < 0) {
                value = (value * 31) ^ 0x1234;
                i++;
                continue;
            }
            value = (value * 17) ^ 0x4567;
            i++;
        }
        return value;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int branchKernel(int value) {
        value = adjust(value);
        value = adjust(value + 1);
        return adjust(value ^ 0x76543210);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int javaCall(int value) {
        return value * 7 + 1;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int callKernel(int value) {
        value = javaCall(value);
        return value < 0 ? value * 31 : value * 17;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int loopKernel(int value) {
        for (int i = 0; i < 4096; i++) value = value * 31 + i;
        return value;
    }

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        sequence = seed;
        int expected = seed;
        for (int call = 0; call < 3; call++) {
            if (call == 1) expected++;
            if (call == 2) expected ^= 0x76543210;
            for (int i = 0; i < 3; i++) {
                expected = expected < 0 ? (expected * 31) ^ 0x1234
                                       : (expected * 17) ^ 0x4567;
            }
        }
        JeandleBenchmarkSupport.check(branchKernel(seed) == expected, "branch kernel");
        expected = seed * 7 + 1;
        expected = expected < 0 ? expected * 31 : expected * 17;
        JeandleBenchmarkSupport.check(callKernel(seed) == expected, "call kernel");
        expected = seed;
        for (int i = 0; i < 4096; i++) expected = expected * 31 + i;
        JeandleBenchmarkSupport.check(loopKernel(seed) == expected, "loop kernel");
    }

    @Benchmark
    public int unrolledBranches() { return branchKernel(seed); }

    @Benchmark
    public int changingBranches() {
        sequence = sequence * 1664525 + 1013904223;
        return branchKernel(sequence);
    }

    @Benchmark
    public int callCovered() { return callKernel(seed); }

    @Benchmark
    public int retainedLoop() { return loopKernel(seed); }
}
