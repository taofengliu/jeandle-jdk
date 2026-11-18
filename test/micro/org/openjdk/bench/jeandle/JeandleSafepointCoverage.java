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
public class JeandleSafepointCoverage {
    @Param({"64", "4096", "262144"})
    public int size;
    private int[] values;
    private int[] output;
    private static volatile int sink;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        values = new int[size];
        output = new int[size];
        for (int i = 0; i < size; i++) values[i] = (i * 17 + 3) ^ (i >>> 3);
        long expected = 0;
        for (int i = 0; i < size; i++) expected += values[i];
        JeandleBenchmarkSupport.check(counted(values) == expected, "counted");
        expected = 0;
        for (int i = 0; i < size; i += (values[i] & 2) + 1) {
            expected += (values[i] & 1) == 0 ? values[i] : -values[i];
        }
        JeandleBenchmarkSupport.check(split(values, output) == expected, "split");
        expected = 0;
        for (int i = 0; i < size; i++) {
            expected += (values[i] & 1) == 0 ? values[i] + 1 : values[i];
        }
        JeandleBenchmarkSupport.check(partialCall(values) == expected, "partial call");
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long counted(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) sum += values[i];
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long split(int[] values, int[] output) {
        long sum = 0;
        int i = 0;
        while (i < values.length) {
            int value = values[i];
            if ((value & 1) == 0) {
                sum += value;
                output[i] = value * 7;
                i += (value & 2) + 1;
                continue;
            }
            sum -= value;
            output[i] = value * 3;
            i += (value & 2) + 1;
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int javaCall(int value) {
        sink = value;
        return value + 1;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long partialCall(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            if ((value & 1) == 0) {
                sum += javaCall(value);
                continue;
            }
            sum += value;
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long nested(int[] values) {
        long sum = 0;
        for (int outer = 0; outer < 8; outer++) {
            int i = 0;
            do {
                sum += values[i] ^ outer;
                i += (values[i] & 2) + 1;
            } while (i < values.length);
        }
        return sum;
    }

    @Benchmark
    public long counted() { return counted(values); }

    @Benchmark
    public long split() { return split(values, output); }

    @Benchmark
    public long partialCall() { return partialCall(values); }

    @Benchmark
    public long nested() { return nested(values); }
}
