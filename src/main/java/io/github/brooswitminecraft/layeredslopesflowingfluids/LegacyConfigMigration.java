package io.github.brooswitminecraft.layeredslopesflowingfluids;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Dynamic Terrain's pre-split {@code erosionEnabled}/{@code waterSamplesPerSecond} choices
 * directly out of its own server config file on disk, the same approach Smooth Slopes uses for
 * its own extracted {@code worldgenSmoothing} key (MINECRAFT-500). Dynamic Terrain's water
 * erosion moved to this mod (MINECRAFT-513); a server upgrading from before that split may still
 * have {@code config/dynamicterrain-server.toml} on disk with these explicitly set -- that file
 * is never rewritten or deleted by this migration, only read.
 */
final class LegacyConfigMigration {
    // Both patterns tolerate an optional trailing "# ..." TOML comment after the value (valid
    // TOML -- a comment runs to end of line) and must still extract the real value from it, not
    // merely avoid throwing (coordinator review, MINECRAFT-514 comment 53149): a value followed
    // by a comment is not the same thing as an absent value.
    private static final Pattern EROSION_ENABLED = Pattern.compile("(?m)^\\s*erosionEnabled\\s*=\\s*(true|false)\\s*(#.*)?$");
    private static final Pattern WATER_SAMPLES_PER_SECOND = Pattern.compile("(?m)^\\s*waterSamplesPerSecond\\s*=\\s*(\\d+)\\s*(#.*)?$");

    private LegacyConfigMigration() {}

    /** @return Dynamic Terrain's legacy master erosion switch, or {@code null} if the file is missing, unreadable, or doesn't set the key. */
    static Boolean readLegacyErosionEnabled(Path legacyConfigFile) {
        String text = readOrNull(legacyConfigFile);
        if (text == null) {
            return null;
        }
        Matcher matcher = EROSION_ENABLED.matcher(text);
        return matcher.find() ? Boolean.valueOf(matcher.group(1)) : null;
    }

    /**
     * @return Dynamic Terrain's legacy {@code waterSamplesPerSecond}, or {@code null} if the file
     *     is missing, unreadable, doesn't set the key (a trailing {@code # comment} is tolerated
     *     and does not count as "doesn't set"), or sets it to a value too large for an
     *     {@code int} -- {@code \d+} matches digit runs of any length, and this is read from
     *     {@link FlowingFluidsConfig}'s static initializer, so a value like
     *     {@code 99999999999999} must fall back to {@code null} rather than throw
     *     {@link NumberFormatException} out of class init and take the whole mod down.
     */
    static Integer readLegacyWaterSamplesPerSecond(Path legacyConfigFile) {
        String text = readOrNull(legacyConfigFile);
        if (text == null) {
            return null;
        }
        Matcher matcher = WATER_SAMPLES_PER_SECOND.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readOrNull(Path legacyConfigFile) {
        if (!Files.isRegularFile(legacyConfigFile)) {
            return null;
        }
        try {
            return Files.readString(legacyConfigFile);
        } catch (IOException e) {
            // An unreadable legacy file falls back to the un-migrated default rather than crashing
            // mod startup over a config file this mod does not own.
            return null;
        }
    }
}
