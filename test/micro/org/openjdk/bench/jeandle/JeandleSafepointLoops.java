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
public class JeandleSafepointLoops {
    @Param({"64", "4096", "262144"})
    public int size;
    private int[] values;
    private int[] output;
    private static volatile int selector;

    @Setup
    public void setup() {
        JeandleBenchmarkSupport.requireJavaBlackhole();
        values = new int[size];
        output = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = (i * 17 + 3) | 1;
        }
        selector = 0;
        long sum = 0;
        long irregular = 0;
        long nested = 0;
        for (int value : values) sum += value;
        for (int i = 0; i < size; i += (values[i] & 3) | 1) irregular += values[i];
        for (int j = 0; j < 4; j++) {
            for (int value : values) nested += value ^ j;
        }
        JeandleBenchmarkSupport.check(countedKernel(values) == sum, "counted loop");
        JeandleBenchmarkSupport.check(widenedKernel(values, size) == sum, "widened loop");
        JeandleBenchmarkSupport.check(irregularKernel(values) == irregular, "irregular loop");
        JeandleBenchmarkSupport.check(currentIVKernel(values) == sum + values[0], "current IV");
        JeandleBenchmarkSupport.check(coveredKernel(values) == sum + size, "covered calls");
        JeandleBenchmarkSupport.check(conditionalKernel(values) == sum, "conditional call");
        JeandleBenchmarkSupport.check(nestedKernel(values) == nested, "nested loops");
        vectorKernel(values, output);
        for (int i = 0; i < size; i++) {
            JeandleBenchmarkSupport.check(output[i] == ((values[i] * 7) ^ (values[i] >>> 3)),
                                          "vector loop");
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long countedKernel(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long widenedKernel(int[] values, long limit) {
        long sum = 0;
        for (int i = 0; (long) i < limit; i++) {
            sum += values[i];
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long irregularKernel(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i += (values[i] & 3) | 1) {
            sum += values[i];
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long currentIVKernel(int[] values) {
        int i = 0;
        long sum = 0;
        do {
            sum += values[i % values.length];
        } while (i++ < values.length);
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int firstCall(int value) {
        return value + 1;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int secondCall(int value) {
        return value - 1;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long coveredKernel(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            if ((selector & 1) == 0) {
                sum += firstCall(values[i]);
            } else {
                sum += secondCall(values[i]);
            }
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long conditionalKernel(int[] values) {
        long sum = 0;
        for (int i = 0; i < values.length; i++) {
            if ((selector & 1) != 0) {
                sum += firstCall(values[i]);
            }
            sum += values[i];
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long nestedKernel(int[] values) {
        long sum = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < values.length; i++) {
                sum += values[i] ^ j;
            }
        }
        return sum;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int vectorKernel(int[] values, int[] output) {
        for (int i = 0; i < values.length; i++) {
            output[i] = (values[i] * 7) ^ (values[i] >>> 3);
        }
        return output[output.length - 1];
    }

    @Benchmark public long counted() { return countedKernel(values); }
    @Benchmark public long widened() { return widenedKernel(values, size); }
    @Benchmark public long irregular() { return irregularKernel(values); }
    @Benchmark public long currentIV() { return currentIVKernel(values); }
    @Benchmark public long covered() { return coveredKernel(values); }
    @Benchmark public long conditional() { return conditionalKernel(values); }
    @Benchmark public long nested() { return nestedKernel(values); }
    @Benchmark public int vector() { return vectorKernel(values, output); }
}
