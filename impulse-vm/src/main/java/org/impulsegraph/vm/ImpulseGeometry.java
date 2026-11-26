package org.impulsegraph.vm;

/**
 * Configurable metamorphic frontier & morsel geometry thresholds for ImpulseVM
 * v0.11.0.
 *
 * <p>
 * Mirrors {@code V11_types.tier_geometry} in the OCaml reference oracle and
 * supports metamorphic profiles {@code P0}..{@code P4} as well as
 * {@code .tier_thresholds} overrides.
 */
public record ImpulseGeometry(int tier0Max, int pageShift, int tier2Denom, int morselMax, int maxDop) {

	public static final ImpulseGeometry P0_DEFAULT = new ImpulseGeometry(64, 12, 64, 2048, 1);
	public static final ImpulseGeometry P1_FORCE_TIER1 = new ImpulseGeometry(2, 2, 2, 2048, 1);
	public static final ImpulseGeometry P2_FORCE_TIER2 = new ImpulseGeometry(0, 6, 0, 2048, 1);
	public static final ImpulseGeometry P3_MICRO_MORSEL = new ImpulseGeometry(64, 12, 64, 3, 1);
	public static final ImpulseGeometry P4_PARALLEL_MORSEL = new ImpulseGeometry(2, 2, 4, 4, 4);

	public static ImpulseGeometry ofProfile(String profile) {
		if (profile == null) {
			return P0_DEFAULT;
		}
		return switch (profile) {
			case "P0" -> P0_DEFAULT;
			case "P1" -> P1_FORCE_TIER1;
			case "P2" -> P2_FORCE_TIER2;
			case "P3" -> P3_MICRO_MORSEL;
			case "P4" -> P4_PARALLEL_MORSEL;
			default -> throw new IllegalArgumentException("Unknown metamorphic profile: " + profile);
		};
	}

	/**
	 * Compute normative physical frontier tier matching
	 * {@code V11_types.compute_tier}.
	 */
	public int computeTier(long domainCard, int card, boolean isAllSpan) {
		if (card == 0) {
			return ImpulseFrontier.TIER_EMPTY;
		}
		if (isAllSpan && card == domainCard) {
			return ImpulseFrontier.TIER_ALL_SPAN;
		}
		if (card <= tier0Max) {
			return ImpulseFrontier.TIER0_INLINE;
		}
		if (tier2Denom == 0 || (long) card * (long) tier2Denom >= domainCard || domainCard <= (1L << pageShift)) {
			return ImpulseFrontier.TIER2_DENSE;
		}
		return ImpulseFrontier.TIER1_PAGED;
	}
}
