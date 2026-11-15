package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyConfigMigrationTest {
    @Test
    void missingLegacyFileReadsAsNull(@TempDir Path dir) {
        Path file = dir.resolve("dynamicterrain-server.toml");
        assertNull(LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertNull(LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void legacyFileWithoutTheKeysReadsAsNull(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "caveInsEnabled = false\n");
        assertNull(LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertNull(LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void legacyFileWithExplicitValuesReadsThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = true\n\twaterSamplesPerSecond = 256\n");
        assertEquals(Boolean.TRUE, LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertEquals(Integer.valueOf(256), LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void legacyFileWithExplicitFalseAndZeroReadsThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");
        assertEquals(Boolean.FALSE, LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertEquals(Integer.valueOf(0), LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void aDirectoryWhereTheFileShouldBeReadsAsNullRatherThanThrowing(@TempDir Path dir) throws IOException {
        Path asDirectory = dir.resolve("dynamicterrain-server.toml");
        Files.createDirectory(asDirectory);
        assertNull(LegacyConfigMigration.readLegacyErosionEnabled(asDirectory));
        assertNull(LegacyConfigMigration.readLegacyWaterSamplesPerSecond(asDirectory));
    }

    @Test
    void anInlineTomlCommentAfterTheValueStillReadsTheRealValue(@TempDir Path dir) throws IOException {
        // MINECRAFT-514 coordinator review (comment 53149): a trailing "# ..." is a valid TOML
        // comment, not an invalid value -- treating it as absent silently discarded a real
        // explicit legacy choice. Both patterns now tolerate it and must still extract true/64.
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = true # keep water erosion on\nwaterSamplesPerSecond = 64 # tuned down\n");
        assertEquals(Boolean.TRUE, LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertEquals(Integer.valueOf(64), LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void anInlineTomlCommentOnAFalseAndZeroValueStillReadsThem(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = false # explicitly off\nwaterSamplesPerSecond = 0 # disabled\n");
        assertEquals(Boolean.FALSE, LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertEquals(Integer.valueOf(0), LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void outOfIntRangeSamplesPerSecondReadsAsNullInsteadOfThrowing(@TempDir Path dir) throws IOException {
        // MINECRAFT-514: \d+ matches digit runs of any length, so a value past Integer.MAX_VALUE
        // used to throw NumberFormatException straight out of FlowingFluidsConfig's static
        // initializer, taking the whole mod down at class load. erosionEnabled is unaffected --
        // only the integer key can overflow.
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = true\nwaterSamplesPerSecond = 99999999999999\n");
        assertEquals(Boolean.TRUE, LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertNull(LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }

    @Test
    void invalidNonNumericValuesReadAsNullRatherThanThrowing(@TempDir Path dir) throws IOException {
        // Neither regex matches a value outside its own expected shape (erosionEnabled requires
        // exactly "true"/"false"; the samples pattern requires only digits), so an invalid value
        // must read as "never set" rather than throw.
        Path file = dir.resolve("dynamicterrain-server.toml");
        Files.writeString(file, "erosionEnabled = maybe\nwaterSamplesPerSecond = -5\n");
        assertNull(LegacyConfigMigration.readLegacyErosionEnabled(file));
        assertNull(LegacyConfigMigration.readLegacyWaterSamplesPerSecond(file));
    }
}
