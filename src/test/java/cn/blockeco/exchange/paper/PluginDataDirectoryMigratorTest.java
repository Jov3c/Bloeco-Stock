package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDataDirectoryMigratorTest {
    @TempDir Path plugins;

    @Test void moves_the_previous_BlockStock_directory_when_bloeco_stock_directory_is_absent() throws Exception {
        Path legacy = Files.createDirectories(plugins.resolve("BlockStock"));
        Files.writeString(legacy.resolve("blockeco.db"), "sqlite-data");

        assertThat(new PluginDataDirectoryMigrator(Files::move).migrate(plugins))
            .isEqualTo(MigrationResult.MIGRATED);

        assertThat(plugins.resolve("Bloeco-Stock/blockeco.db")).hasContent("sqlite-data");
        assertThat(legacy).doesNotExist();
    }

    @Test void preserves_both_directories_when_target_already_exists() throws Exception {
        Path legacy = Files.createDirectories(plugins.resolve("BlockecoExchange"));
        Path target = Files.createDirectories(plugins.resolve("Bloeco-Stock"));
        Files.writeString(legacy.resolve("legacy.db"), "legacy-data");
        Files.writeString(target.resolve("current.db"), "current-data");

        assertThat(new PluginDataDirectoryMigrator(Files::move).migrate(plugins))
            .isEqualTo(MigrationResult.SKIPPED_TARGET_EXISTS);

        assertThat(legacy.resolve("legacy.db")).hasContent("legacy-data");
        assertThat(target.resolve("current.db")).hasContent("current-data");
    }

    @Test void propagates_directory_move_failure_without_creating_target() throws Exception {
        Path legacy = Files.createDirectories(plugins.resolve("BlockStock"));
        Files.writeString(legacy.resolve("blockeco.db"), "sqlite-data");

        assertThatThrownBy(() -> new PluginDataDirectoryMigrator((source, target) -> {
            throw new IOException("disk unavailable");
        }).migrate(plugins)).isInstanceOf(IOException.class).hasMessage("disk unavailable");

        assertThat(plugins.resolve("Bloeco-Stock")).doesNotExist();
        assertThat(legacy.resolve("blockeco.db")).hasContent("sqlite-data");
    }

    @Test void continues_to_migrate_the_original_BlockecoExchange_directory() throws Exception {
        Path legacy = Files.createDirectories(plugins.resolve("BlockecoExchange"));
        Files.writeString(legacy.resolve("blockeco.db"), "sqlite-data");

        assertThat(new PluginDataDirectoryMigrator(Files::move).migrate(plugins))
            .isEqualTo(MigrationResult.MIGRATED);

        assertThat(plugins.resolve("Bloeco-Stock/blockeco.db")).hasContent("sqlite-data");
    }
}
