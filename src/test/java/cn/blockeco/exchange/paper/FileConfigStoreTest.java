package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FileConfigStoreTest {
    @Test void persists_only_the_requested_value_and_preserves_other_configuration_keys() throws Exception {
        Path directory = Files.createTempDirectory("blockstock-config-store-"); Path target = directory.resolve("config.yml");
        try {
            Files.writeString(target, "company:\n  minimum-capital: '10000.00'\n  registration-fee: '1000.00'\nmessages:\n  no-permission: '保留'\n");
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(target.toFile());
            new FileConfigStore(configuration, target).persistMinimumCapital("25000.50");
            YamlConfiguration saved = YamlConfiguration.loadConfiguration(target.toFile());
            assertThat(saved.getString("company.minimum-capital")).isEqualTo("25000.50");
            assertThat(saved.getString("company.registration-fee")).isEqualTo("1000.00"); assertThat(saved.getString("messages.no-permission")).isEqualTo("保留");
            assertThat(configuration.getString("company.minimum-capital")).isEqualTo("25000.50");
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory); }
    }

    @Test void save_or_move_failure_keeps_file_and_live_configuration_at_the_old_value() throws Exception {
        Path directory = Files.createTempDirectory("blockstock-config-store-"); Path target = directory.resolve("config.yml");
        try {
            String original = "company:\n  minimum-capital: '10000.00'\n  registration-fee: '1000.00'\n"; Files.writeString(target, original);
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(target.toFile());
            FileConfigStore failingSave = new FileConfigStore(configuration, target, (value, file) -> { throw new IOException("disk full"); }, FileConfigStore.SYSTEM_FILES);
            assertThatThrownBy(() -> failingSave.persistMinimumCapital("25000.50")).isInstanceOf(IOException.class);
            assertThat(Files.readString(target)).isEqualTo(original); assertThat(configuration.getString("company.minimum-capital")).isEqualTo("10000.00");
            FileConfigStore failingMove = new FileConfigStore(configuration, target, FileConfigStore.SYSTEM_WRITER, new FileConfigStore.FileOperations() {
                @Override public Path createTempFile(Path parent, String prefix) throws IOException { return Files.createTempFile(parent, prefix, ".tmp"); }
                @Override public void atomicReplace(Path source, Path destination) throws IOException { throw new IOException("move blocked"); }
                @Override public void replace(Path source, Path destination) throws IOException { throw new IOException("move blocked"); }
                @Override public void deleteIfExists(Path path) throws IOException { Files.deleteIfExists(path); }
            });
            assertThatThrownBy(() -> failingMove.persistMinimumCapital("25000.50")).isInstanceOf(IOException.class);
            assertThat(Files.readString(target)).isEqualTo(original); assertThat(configuration.getString("company.minimum-capital")).isEqualTo("10000.00");
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory); }
    }
}
