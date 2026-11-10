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

    private MigrationPolicy() {}

    /**
     * Dynamic Terrain's water erosion actually ran only when BOTH its master
     * {@code erosionEnabled} switch was on AND {@code waterSamplesPerSecond} was nonzero
     * (0 disables sampling regardless of the master switch). Any other combination -- absent,
     * explicitly off, or zero samples -- carries forward as off, matching this mod's own
     * default-off safety policy: never silently turn new erosion on for an upgrading server.
     *
     * @param legacyErosionEnabled Dynamic Terrain's master switch, or {@code null} if never set.
     * @param legacyWaterSamplesPerSecond Dynamic Terrain's sample count, or {@code null} if never set.
     * @return the default this mod's own {@code erosionEnabled} key should take on first write.
     */
    static boolean defaultErosionEnabled(Boolean legacyErosionEnabled, Integer legacyWaterSamplesPerSecond) {
        if (!Boolean.TRUE.equals(legacyErosionEnabled)) {
            return false;
        }
        return legacyWaterSamplesPerSecond == null || legacyWaterSamplesPerSecond > 0;
    }

    /**
     * @param legacyWaterSamplesPerSecond Dynamic Terrain's sample count, or {@code null} if never set.
     * @return the default this mod's own {@code samplesPerSecond} key should take on first write.
     */
    static int defaultSamplesPerSecond(Integer legacyWaterSamplesPerSecond) {
        return legacyWaterSamplesPerSecond != null ? legacyWaterSamplesPerSecond : DEFAULT_SAMPLES_PER_SECOND;
    }
}
