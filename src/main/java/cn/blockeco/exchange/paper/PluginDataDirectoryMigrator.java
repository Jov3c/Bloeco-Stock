package cn.blockeco.exchange.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PluginDataDirectoryMigrator {
    private static final String TARGET_DIRECTORY = "Bloeco-Stock";
    private static final String PREVIOUS_DIRECTORY = "BlockStock";
    private static final String ORIGINAL_LEGACY_DIRECTORY = "BlockecoExchange";
    private final DirectoryMover mover;

    public PluginDataDirectoryMigrator(DirectoryMover mover) {
        this.mover = mover;
    }

    public MigrationResult migrate(Path pluginsDirectory) throws IOException {
        Path target = pluginsDirectory.resolve(TARGET_DIRECTORY);
        if (Files.exists(target)) return MigrationResult.SKIPPED_TARGET_EXISTS;
        Path legacy = pluginsDirectory.resolve(PREVIOUS_DIRECTORY);
        if (!Files.exists(legacy)) legacy = pluginsDirectory.resolve(ORIGINAL_LEGACY_DIRECTORY);
        if (!Files.exists(legacy)) return MigrationResult.NO_LEGACY_DIRECTORY;
        mover.move(legacy, target);
        return MigrationResult.MIGRATED;
    }

    @FunctionalInterface
    public interface DirectoryMover {
        void move(Path source, Path target) throws IOException;
    }
}
