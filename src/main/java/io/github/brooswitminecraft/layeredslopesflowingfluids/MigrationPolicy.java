package io.github.brooswitminecraft.layeredslopesflowingfluids;

/**
 * Pure decision for migrating Dynamic Terrain's pre-split water-erosion choices into this mod's
 * own config defaults, free of file IO (see {@link LegacyConfigMigration} for the actual read).
 * MINECRAFT-513: upgrading a server that had water erosion genuinely running (or genuinely off)
 * under Dynamic Terrain must carry that choice forward, not silently flip it just because the
 * feature now lives in a different mod's config file.
 */
final class MigrationPolicy {
    private static final int DEFAULT_SAMPLES_PER_SECOND = 128;
    /**
     * MUST match {@code FlowingFluidsConfig.SAMPLES_PER_SECOND}'s own {@code defineInRange}
     * upper bound exactly. Not referenced directly from there (and vice versa) so this class
     * stays free of any NeoForge/Minecraft dependency -- see the class doc.
     */
    private static final int MAX_SAMPLES_PER_SECOND = 4096;

    private MigrationPolicy() {}

    /**
     * Dynamic Terrain's water erosion actually ran only when BOTH its master
     * {@code erosionEnabled} switch was on AND {@code waterSamplesPerSecond} was nonzero
     * (0 disables sampling regardless of the master switch). Any other combination -- absent,
     * explicitly off, or zero samples -- carries forward as off, matching this mod's own
     * default-off safety policy: never silently turn new erosion on for an upgrading server.
     * An out-of-spec sample count (negative, or above this mod's own 4096 upper bound -- MINECRAFT-514
     * coordinator review, comment 53153) is normalized exactly like "never set" (see {@link
     * #normalizeSamplesPerSecond}) before this decision is made.
     *
     * @param legacyErosionEnabled Dynamic Terrain's master switch, or {@code null} if never set.
     * @param legacyWaterSamplesPerSecond Dynamic Terrain's sample count, or {@code null} if never set.
     * @return the default this mod's own {@code erosionEnabled} key should take on first write.
     */
    static boolean defaultErosionEnabled(Boolean legacyErosionEnabled, Integer legacyWaterSamplesPerSecond) {
        if (!Boolean.TRUE.equals(legacyErosionEnabled)) {
            return false;
        }
        Integer samples = normalizeSamplesPerSecond(legacyWaterSamplesPerSecond);
        return samples == null || samples > 0;
    }

    /**
     * @param legacyWaterSamplesPerSecond Dynamic Terrain's sample count, or {@code null} if never set.
     * @return the default this mod's own {@code samplesPerSecond} key should take on first
     *     write -- always within {@code [0, 4096]}, {@code FlowingFluidsConfig.SAMPLES_PER_SECOND}'s
     *     own {@code defineInRange} bound, since this feeds both its static-init default (passed
     *     straight to {@code defineInRange}, which would reject an out-of-range default) and a
     *     per-world override's live {@code .set()}/persisted file.
     */
    static int defaultSamplesPerSecond(Integer legacyWaterSamplesPerSecond) {
        Integer samples = normalizeSamplesPerSecond(legacyWaterSamplesPerSecond);
        return samples != null ? samples : DEFAULT_SAMPLES_PER_SECOND;
    }

    /**
     * An out-of-spec legacy value -- negative, or above {@code MAX_SAMPLES_PER_SECOND} -- is
     * treated exactly like "never set": {@link LegacyConfigMigration#readLegacyWaterSamplesPerSecond}
     * already falls back to {@code null} for a value too large for an {@code int} at all, but a
     * value that DOES fit in an {@code int} (4097, 5000, {@code Integer.MAX_VALUE}) still used to
     * pass straight through to {@code defineInRange}'s own default (which only validates its
     * default is in-range, it does not clamp -- an invalid default there is a real bug, not
     * merely cosmetic) or into a per-world {@code .set()}/persisted override file.
     */
    private static Integer normalizeSamplesPerSecond(Integer legacyWaterSamplesPerSecond) {
        if (legacyWaterSamplesPerSecond == null
                || legacyWaterSamplesPerSecond < 0
                || legacyWaterSamplesPerSecond > MAX_SAMPLES_PER_SECOND) {
            return null;
        }
        return legacyWaterSamplesPerSecond;
    }
}
