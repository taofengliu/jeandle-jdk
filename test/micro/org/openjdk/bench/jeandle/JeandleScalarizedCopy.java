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
 * Array copies into FRESH destinations that never escape, at CONSTANT
 * lengths: PEA folds the jeandle.copy pseudo call into virtual field state
 * and eliminates both the copy and the allocation. The copy length must be
 * a compile-time constant for the fold (a @Param value is a runtime int and
 * stays conservative). Runs with Jeandle PEA enabled; JeandleArrayCopy/Of
 * (PEA off, pre-allocated destinations) remain the CopySpecialization
 * companions. Every benchmark consumes each copied element so the result
 * is not dead-code-eliminated.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:+JeandleDoPEA"})
public class JeandleScalarizedCopy {

    private int[] intsSource;
    private long[] longsSource;
    private Object[] objectsSource;

    @Setup
    public void setup() {
        intsSource = new int[72];
        longsSource = new long[72];
        objectsSource = new Object[72];
        for (int i = 0; i < 72; i++) {
            intsSource[i] = i * 31 + 19;
            longsSource[i] = i * 6364136223846793005L + 1442695040888963407L;
            objectsSource[i] = Integer.valueOf(i);
        }
    }

    private long sum(int[] a, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
        }
        return sum;
    }

    @Benchmark
    public long freshIntCopy1() {
        int[] dst = new int[1];
        System.arraycopy(intsSource, 2, dst, 0, 1);
        return sum(dst, 1);
    }

    @Benchmark
    public long freshIntCopy4() {
        int[] dst = new int[4];
        System.arraycopy(intsSource, 2, dst, 0, 4);
        return sum(dst, 4);
    }

    @Benchmark
    public long freshIntCopy8() {
        int[] dst = new int[8];
        System.arraycopy(intsSource, 2, dst, 0, 8);
        return sum(dst, 8);
    }

    @Benchmark
    public long freshIntCopy16() {
        int[] dst = new int[16];
        System.arraycopy(intsSource, 2, dst, 0, 16);
        return sum(dst, 16);
    }

    @Benchmark
    public long freshIntCopy64() {
        int[] dst = new int[64];
        System.arraycopy(intsSource, 2, dst, 0, 64);
        return sum(dst, 64);
    }

    @Benchmark
    public long freshLongCopy8() {
        long[] dst = new long[8];
        System.arraycopy(longsSource, 2, dst, 0, 8);
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += dst[i];
        }
        return sum;
    }

    @Benchmark
    public long freshObjectCopy8() {
        Object[] dst = new Object[8];
        System.arraycopy(objectsSource, 2, dst, 0, 8);
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += System.identityHashCode(dst[i]) & 1;
        }
        return sum;
    }

    @Benchmark
    public long freshCloneInt8() {
        int[] src = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] c = src.clone();
        return sum(c, 8);
    }

    @Benchmark
    public long freshCloneObject8() {
        Object[] src = {objectsSource[0], objectsSource[1], objectsSource[2],
                        objectsSource[3], objectsSource[4], objectsSource[5],
                        objectsSource[6], objectsSource[7]};
        Object[] c = src.clone();
        long sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += System.identityHashCode(c[i]) & 1;
        }
        return sum;
    }

    @Benchmark
    public long freshIntSelfOverlap8() {
        int[] a = new int[12];
        System.arraycopy(intsSource, 0, a, 0, 12);
        System.arraycopy(a, 0, a, 1, 8);
        return sum(a, 12);
    }
}
