package cn.blockeco.exchange.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginDataDirectoryMigrator {
    private final DirectoryMover mover;

    public PluginDataDirectoryMigrator(DirectoryMover mover) {
        this.mover = mover;
    }

    public MigrationResult migrate(Path pluginsDirectory) throws IOException {
        Path target = pluginsDirectory.resolve("BlockStock");
        if (Files.exists(target)) return MigrationResult.SKIPPED_TARGET_EXISTS;
        Path legacy = pluginsDirectory.resolve("BlockecoExchange");
        if (!Files.exists(legacy)) return MigrationResult.NO_LEGACY_DIRECTORY;
        mover.move(legacy, target);
        return MigrationResult.MIGRATED;
    }

    @FunctionalInterface
    public interface DirectoryMover {
        void move(Path source, Path target) throws IOException;
    }
}
