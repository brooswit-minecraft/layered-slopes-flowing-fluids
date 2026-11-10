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
}
