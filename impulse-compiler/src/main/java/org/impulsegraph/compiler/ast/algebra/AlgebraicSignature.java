package org.impulsegraph.compiler.ast.algebra;

import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.RelationStatistics.Multiplicity;

/**
 * Universal Algebraic Signature attached to AST nodes during type and property inference.
 * Encapsulates interval bounds, morphism classifications, monoidal and lattice axioms.
 */
public record AlgebraicSignature(
        IntervalBound interval,
        MorphismClass morphism,
        int algebraFlags,
        Monotonicity monotonicity,
        int homomorphismFlags,
        boolean isPure,
        boolean isConstantKnown,
        boolean constantBoolVal,
        long constantIntVal,
        double constantFloatVal
) {

    public enum MorphismClass {
        GENERAL,
        FUNCTIONAL, // Out-Degree <= 1 (Many-to-One)
        INJECTIVE,  // In-Degree <= 1 (One-to-Many)
        BIJECTIVE   // In-Degree <= 1 && Out-Degree <= 1 (One-to-One)
    }

    public static final int ALG_NONE         = 0x00;
    public static final int ALG_SEMIGROUP    = 0x01; // Associative: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
    public static final int ALG_MONOID       = 0x02; // Associative + Identity (ε)
    public static final int ALG_COMMUTATIVE  = 0x04; // Commutative: a ⊕ b = b ⊕ a
    public static final int ALG_IDEMPOTENT   = 0x08; // Idempotent (Semilattice): x ⊕ x = x
    public static final int ALG_DISTRIBUTIVE = 0x10; // Distributive: a ⊗ (b ⊕ c) = (a ⊗ b) ⊕ (a ⊗ c)
    public static final int ALG_ABSORBING    = 0x20; // Has Absorbing Annihilator (⊤ ⊕ x = ⊤)

    public static final int HOMO_NONE              = 0x00;
    public static final int HOMO_COMMUTES_WITH_MAX = 0x01; // f(max(S)) == max(f(S))
    public static final int HOMO_COMMUTES_WITH_MIN = 0x02; // f(min(S)) == min(f(S))
    public static final int HOMO_COMMUTES_WITH_SUM = 0x04; // f(sum(S)) == sum(f(S))
    public static final int HOMO_DISTRIBUTES_OR    = 0x08; // f(a || b) == f(a) || f(b)
    public static final int HOMO_DISTRIBUTES_AND   = 0x10; // f(a && b) == f(a) && f(b)

    public record IntervalBound(
            long minInt,
            long maxInt,
            double minFloat,
            double maxFloat,
            boolean isBounded
    ) {
        public static final IntervalBound UNBOUNDED = new IntervalBound(
                Long.MIN_VALUE, Long.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, false
        );

        public static IntervalBound ofInt(long min, long max) {
            return new IntervalBound(min, max, (double) min, (double) max, true);
        }

        public static IntervalBound ofFloat(double min, double max) {
            return new IntervalBound((long) min, (long) max, min, max, true);
        }

        public static IntervalBound ofConstantInt(long val) {
            return new IntervalBound(val, val, (double) val, (double) val, true);
        }

        public static IntervalBound ofConstantFloat(double val) {
            return new IntervalBound((long) val, (long) val, val, val, true);
        }
    }

    public static AlgebraicSignature defaultGeneral() {
        return new AlgebraicSignature(
                IntervalBound.UNBOUNDED,
                MorphismClass.GENERAL,
                ALG_NONE,
                Monotonicity.MONO_NONE,
                HOMO_NONE,
                true,
                false,
                false,
                0,
                0.0
        );
    }

    public static AlgebraicSignature ofConstantBool(boolean val) {
        return new AlgebraicSignature(
                IntervalBound.UNBOUNDED,
                MorphismClass.GENERAL,
                ALG_NONE,
                Monotonicity.MONO_CONSTANT,
                HOMO_DISTRIBUTES_OR | HOMO_DISTRIBUTES_AND,
                true,
                true,
                val,
                val ? 1 : 0,
                val ? 1.0 : 0.0
        );
    }

    public static AlgebraicSignature ofConstantInt(long val) {
        return new AlgebraicSignature(
                IntervalBound.ofConstantInt(val),
                MorphismClass.GENERAL,
                ALG_NONE,
                Monotonicity.MONO_CONSTANT,
                HOMO_COMMUTES_WITH_MAX | HOMO_COMMUTES_WITH_MIN,
                true,
                true,
                val != 0,
                val,
                (double) val
        );
    }

    public static AlgebraicSignature ofConstantFloat(double val) {
        return new AlgebraicSignature(
                IntervalBound.ofConstantFloat(val),
                MorphismClass.GENERAL,
                ALG_NONE,
                Monotonicity.MONO_CONSTANT,
                HOMO_COMMUTES_WITH_MAX | HOMO_COMMUTES_WITH_MIN,
                true,
                true,
                val != 0.0,
                (long) val,
                val
        );
    }

    public static AlgebraicSignature ofMorphism(Multiplicity multiplicity) {
        MorphismClass morphismClass = switch (multiplicity) {
            case ONE_TO_ONE -> MorphismClass.BIJECTIVE;
            case MANY_TO_ONE -> MorphismClass.FUNCTIONAL;
            case ONE_TO_MANY -> MorphismClass.INJECTIVE;
            default -> MorphismClass.GENERAL;
        };

        return new AlgebraicSignature(
                IntervalBound.UNBOUNDED,
                morphismClass,
                ALG_NONE,
                Monotonicity.MONO_NONE,
                HOMO_NONE,
                true,
                false,
                false,
                0,
                0.0
        );
    }

    public boolean isIdempotent() {
        return (algebraFlags & ALG_IDEMPOTENT) != 0;
    }

    public boolean isCommutative() {
        return (algebraFlags & ALG_COMMUTATIVE) != 0;
    }

    public boolean isAssociative() {
        return (algebraFlags & ALG_SEMIGROUP) != 0 || (algebraFlags & ALG_MONOID) != 0;
    }

    public boolean commutesWithMax() {
        return (homomorphismFlags & HOMO_COMMUTES_WITH_MAX) != 0;
    }

    public boolean commutesWithMin() {
        return (homomorphismFlags & HOMO_COMMUTES_WITH_MIN) != 0;
    }
}
