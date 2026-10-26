/*
 * Copyright (c) 2026, Alibaba Group Holding Limited. All Rights Reserved.
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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Array copies into FRESH destinations that never escape: PEA folds the
 * jeandle.copy pseudo call into virtual field state and eliminates both the
 * copy and the allocation. Runs with Jeandle PEA enabled — unlike
 * JeandleArrayCopy/Of (PEA disabled, pre-allocated destinations), which
 * measure the CopySpecialization path and serve as the regression companion.
 * Every benchmark consumes each copied element so the result is not
 * dead-code-eliminated.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:+JeandleDoPEA"})
public class JeandleScalarizedCopy {
    @Param({"0", "1", "7", "8", "9", "64"})
    public int size;

    private int[] intsSource;
    private long[] longsSource;
    private Object[] objectsSource;
    private int[] overlapSource;

    @Setup
    public void setup() {
        int length = size + 4;
        intsSource = new int[length];
        longsSource = new long[length];
        objectsSource = new Object[length];
        overlapSource = new int[length];
        for (int i = 0; i < length; i++) {
            intsSource[i] = i * 31 + 19;
            longsSource[i] = i * 6364136223846793005L + 1442695040888963407L;
            objectsSource[i] = Integer.valueOf(i);
            overlapSource[i] = i * 17 + 3;
        }
    }

    @Benchmark
    public long freshIntCopy() {
        int[] dst = new int[size];
        System.arraycopy(intsSource, 2, dst, 0, size);
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += dst[i];
        }
        return sum;
    }

    @Benchmark
    public long freshLongCopy() {
        long[] dst = new long[size];
        System.arraycopy(longsSource, 2, dst, 0, size);
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += dst[i];
        }
        return sum;
    }

    @Benchmark
    public long freshObjectCopy() {
        Object[] dst = new Object[size];
        System.arraycopy(objectsSource, 2, dst, 0, size);
        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += System.identityHashCode(dst[i]) & 1;
        }
        return sum;
    }

    @Benchmark
    public long freshIntSelfOverlap() {
        int[] a = new int[size + 4];
        System.arraycopy(overlapSource, 0, a, 0, size + 4);
        System.arraycopy(a, 0, a, 1, size);
        long sum = 0;
        for (int i = 0; i < size + 4; i++) {
            sum += a[i];
        }
        return sum;
    }
}
