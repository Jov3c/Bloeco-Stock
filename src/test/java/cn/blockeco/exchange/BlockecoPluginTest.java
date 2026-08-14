package cn.blockeco.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class BlockecoPluginTest {

    @Test
    void plugin_metadata_entrypoint_exists_and_is_a_java_plugin() {
        Class<?> entrypoint = assertDoesNotThrow(() -> Class.forName(declaredEntrypointClass()));

        assertThat(entrypoint).isAssignableTo(JavaPlugin.class);
    }

    private String declaredEntrypointClass() throws Exception {
        InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertThat(resource).isNotNull();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> line.startsWith("main: "))
                    .map(line -> line.substring("main: ".length()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
