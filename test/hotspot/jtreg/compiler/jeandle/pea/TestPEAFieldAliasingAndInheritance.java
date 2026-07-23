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

/*
 * @test
 * @summary PEA scalar-replaces inherited and hidden fields through aliases,
 *          and materializes the original allocation for an identity consumer
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAFieldAliasingAndInheritance
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;

public class TestPEAFieldAliasingAndInheritance {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAFieldAliasingAndInheritance$TestWrapper";

    public static void main(String[] args) throws Exception {
        Method aliases = TestWrapper.class.getMethod("testAliasedInheritedFields",
                boolean.class, int.class, int.class, long.class, byte.class,
                Object.class, Object.class);
        Method identity = TestWrapper.class.getMethod("testDerivedFieldIdentityUse",
                int.class, long.class, Object.class);
        Method aliasHelper = TestWrapper.class.getMethod("resolvableAlias",
                TestWrapper.Derived.class);
        Method identityConsumer = TestWrapper.class.getMethod("consumeIdentity",
                TestWrapper.Derived.class);
        Method[] targets = {aliases, identity};

        behaviorBuilder(targets, aliasHelper, identityConsumer).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run =
                shapeBuilder(targets, aliasHelper, identityConsumer).run()) {
            assertAliasedFieldsVirtualized(run, aliases);
            assertIdentityUseRetainsOriginalAllocation(run, identity, identityConsumer);
        }
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                          Method aliasHelper,
                                                          Method identityConsumer) {
        return configure(PEATestUtils.shapeRun(WRAPPER, targets), aliasHelper, identityConsumer);
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method aliasHelper,
                                                            Method identityConsumer) {
        return configure(PEATestUtils.behaviorRun(WRAPPER, targets), aliasHelper, identityConsumer);
    }

    private static PEATestUtils.RunBuilder configure(PEATestUtils.RunBuilder builder,
                                                      Method aliasHelper,
                                                      Method identityConsumer) {
        return builder
                .inline(aliasHelper)
                .dontinline(identityConsumer);
    }

    private static void assertAliasedFieldsVirtualized(PEATestUtils.RunResult run,
                                                        Method target) throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = first.before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(first.neverEscapes(), 1, target + ": virtual alias state");
        Asserts.assertEquals(first.partiallyEscapes(), 0, target + ": partial aliases");
        Asserts.assertEquals(first.alwaysEscapes(), 0, target + ": escaping aliases");
        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source Derived allocation");
        Asserts.assertEquals(before.allocations().get(0).key().kind(),
                PEATestUtils.AllocationKind.INSTANCE, target + ": Derived allocation kind");
        Asserts.assertEquals(first.effectCount("EliminateAllocation"), 1L,
                target + ": allocation elimination");
        Asserts.assertTrue(first.effectCount("EliminateStore") >= 6,
                target + ": inherited, hidden, adjacent, and reference stores eliminated");
        after.assertRetainsExactlyOriginalAllocations(before);
        Asserts.assertEquals(run.finalIR(target).loweredAllocCount(), 0,
                target + ": no lowered allocation after scalar replacement");
    }

    private static void assertIdentityUseRetainsOriginalAllocation(PEATestUtils.RunResult run,
                                                                     Method target,
                                                                     Method consumer)
            throws Exception {
        PEATestUtils.PEAReport report = run.report(target);
        PEATestUtils.IRBody before = report.round(0).before();
        PEATestUtils.IRBody after = report.finalAfter();

        Asserts.assertEquals(before.allocations().size(), 1,
                target + ": one source allocation before the identity use");
        before.assertPresent("getelementptr");
        PEATestUtils.AllocationKey original = before.allocations().get(0).key();
        Asserts.assertEquals(original.kind(), PEATestUtils.AllocationKind.INSTANCE,
                target + ": identity consumer allocation kind");
        after.assertRetainsExactlyOriginalAllocations(before, original);
        Asserts.assertEquals(report.round(0).effectCount("Materialize"), 1L,
                target + ": exactly one materialization at the identity consumer");

        PEATestUtils.IRBlock consumerBlock = after.blockContaining(
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
        consumerBlock.assertPresent("store atomic i32");
        consumerBlock.assertBefore("store atomic i32", 0,
                PEATestUtils.MethodId.of(consumer).llvmFunctionName(), 0);
    }

    public static class TestWrapper {
        private static Derived observed;

        public static class Base {
            int inherited;
            long baseLong;
        }

        public static class Derived extends Base {
            int inherited;
            byte adjacent;
            Object reference;
        }

        public static void main(String[] args) throws Exception {
            new Derived();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Object first = new Object();
            Object second = new Object();
            long digest = 0x9E3779B97F4A7C15L;
            for (boolean secondArm : new boolean[] {false, true}) {
                for (int base : new int[] {0, -17, Integer.MAX_VALUE}) {
                    int hidden = base * 3 + 11;
                    long baseLong = 0x0123456789ABCDEFL ^ base;
                    byte adjacent = (byte) (base ^ 0x5A);
                    long actual = testAliasedInheritedFields(secondArm, base, hidden,
                            baseLong, adjacent, first, second);
                    long expected = expectedAliasedFields(secondArm, base, hidden,
                            baseLong, adjacent, first, second);
                    Asserts.assertEquals(actual, expected,
                            "aliases preserve inherited and hidden field state");
                    digest = mix(digest, actual);
                }
            }

            for (int value : new int[] {0, -41, 0x13579BDF}) {
                observed = null;
                Object reference = (value & 1) == 0 ? first : second;
                long actual = testDerivedFieldIdentityUse(value,
                        0x1122334455667788L ^ value, reference);
                Asserts.assertNotEquals(observed, null,
                        "identity consumer receives the materialized object");
                Asserts.assertEquals(observed.inherited, value + 7,
                        "hidden field mutation remains visible through the identity alias");
                Asserts.assertEquals(observed.baseLong,
                        (0x1122334455667788L ^ value) + 9,
                        "inherited long field does not share the hidden field slot");
                Asserts.assertTrue(observed.reference == reference,
                        "reference field identity survives materialization");
                Asserts.assertEquals(actual, expectedIdentityUse(value,
                        0x1122334455667788L ^ value),
                        "identity use result");
                digest = mix(digest, actual);
            }
            System.out.println("PEA-RESULT:" + Long.toUnsignedString(digest, 16));
        }

        public static long testAliasedInheritedFields(boolean secondArm, int base,
                                                       int hidden, long baseLong,
                                                       byte adjacent, Object first,
                                                       Object second) {
            Derived allocation = new Derived();
            Derived directAlias = allocation;
            Base inheritedAlias = allocation;
            directAlias.inherited = hidden;
            inheritedAlias.inherited = base;
            inheritedAlias.baseLong = baseLong;
            allocation.adjacent = adjacent;
            allocation.reference = secondArm ? second : first;

            Derived selected;
            if (secondArm) {
                selected = directAlias;
                selected.adjacent++;
            } else {
                selected = allocation;
                selected.adjacent--;
            }
            Derived helperAlias = resolvableAlias(selected);
            int identities = (allocation == directAlias ? 1 : 0)
                    | (allocation == selected ? 2 : 0)
                    | (inheritedAlias == helperAlias ? 4 : 0);
            identities |= helperAlias.reference == first ? 8
                    : helperAlias.reference == second ? 16 : 0;
            return checksum(inheritedAlias.inherited, helperAlias.inherited,
                    helperAlias.baseLong, helperAlias.adjacent, helperAlias.reference,
                    identities);
        }

        public static Derived resolvableAlias(Derived value) {
            return value;
        }

        public static long testDerivedFieldIdentityUse(int hidden, long baseLong,
                                                        Object reference) {
            Derived value = new Derived();
            value.inherited = hidden;
            value.baseLong = baseLong;
            value.reference = reference;
            int snapshot = value.inherited;
            int identity = consumeIdentity(value);
            value.inherited = snapshot + 7;
            value.baseLong += 9;
            boolean stableIdentity = identity == System.identityHashCode(value);
            return checksum(value.inherited, value.inherited, value.baseLong, (byte) 0,
                    value.reference, stableIdentity && observed == value ? 7 : 0);
        }

        public static int consumeIdentity(Derived value) {
            observed = value;
            return System.identityHashCode(value);
        }

        private static long expectedAliasedFields(boolean secondArm, int base, int hidden,
                                                  long baseLong, byte adjacent, Object first,
                                                  Object second) {
            byte selectedAdjacent = (byte) (secondArm ? adjacent + 1 : adjacent - 1);
            int identities = 7 | (secondArm ? 16 : 8);
            return checksum(base, hidden, baseLong, selectedAdjacent,
                    secondArm ? second : first, identities);
        }

        private static long expectedIdentityUse(int hidden, long baseLong) {
            // The hash value is deliberately not part of the oracle; its stable
            // same-object comparison forces an identity-sensitive use.
            return checksum(hidden + 7, hidden + 7, baseLong + 9, (byte) 0,
                    observed.reference, 7);
        }

        private static long checksum(int base, int hidden, long baseLong, byte adjacent,
                                     Object reference, int identities) {
            long result = base * 0x9E3779B9L + hidden;
            result = Long.rotateLeft(result ^ baseLong, 19);
            result = result * 31 + adjacent;
            result = result * 31 + (reference == null ? 0 : 1);
            return result * 31 + identities;
        }

        private static long mix(long accumulator, long value) {
            return Long.rotateLeft(accumulator ^ value, 13) * 0xD6E8FEB86659FD93L;
        }
    }
}
