package cn.blockeco.exchange.paper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persists one config value without changing the live configuration until replacement succeeds. */
public final class FileConfigStore implements StockAdminConfigCommand.ConfigStore {
    static final ConfigurationWriter SYSTEM_WRITER = (configuration, file) -> configuration.save(file.toFile());
    static final FileOperations SYSTEM_FILES = new FileOperations() {
        @Override public Path createTempFile(Path parent, String prefix) throws IOException { return Files.createTempFile(parent, prefix, ".tmp"); }
        @Override public void atomicReplace(Path source, Path destination) throws IOException { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        @Override public void replace(Path source, Path destination) throws IOException { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
        @Override public void deleteIfExists(Path path) throws IOException { Files.deleteIfExists(path); }
    };
    private final FileConfiguration live; private final Path target; private final ConfigurationWriter writer; private final FileOperations files;
    public FileConfigStore(FileConfiguration live, Path target) { this(live, target, SYSTEM_WRITER, SYSTEM_FILES); }
    FileConfigStore(FileConfiguration live, Path target, ConfigurationWriter writer, FileOperations files) { this.live=live; this.target=target; this.writer=writer; this.files=files; }
    @Override public void persistMinimumCapital(String value) throws IOException {
        Path parent = target.toAbsolutePath().getParent(); if (parent == null) throw new IOException("config target has no parent");
        Path temporary = files.createTempFile(parent, target.getFileName().toString());
        try {
            YamlConfiguration staged = new YamlConfiguration();
            try { staged.loadFromString(live.saveToString()); }
            catch (org.bukkit.configuration.InvalidConfigurationException invalid) { throw new IOException("could not stage config", invalid); }
            staged.set("company.minimum-capital", value);
            writer.save(staged, temporary);
            try { files.atomicReplace(temporary, target); }
            catch (AtomicMoveNotSupportedException unsupported) { files.replace(temporary, target); }
            live.set("company.minimum-capital", value);
        } finally { files.deleteIfExists(temporary); }
    }
    @FunctionalInterface interface ConfigurationWriter { void save(FileConfiguration configuration, Path file) throws IOException; }
    interface FileOperations { Path createTempFile(Path parent, String prefix) throws IOException; void atomicReplace(Path source, Path destination) throws IOException; void replace(Path source, Path destination) throws IOException; void deleteIfExists(Path path) throws IOException; }
}
