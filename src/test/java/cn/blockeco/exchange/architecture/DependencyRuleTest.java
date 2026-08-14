package cn.blockeco.exchange.architecture;

import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyRuleTest {
    @Test
    void reports_forbidden_references_in_a_compiled_violating_fixture() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(DeliberatelyViolatingFixture.class))
            .anyMatch(reference -> reference.contains("org.bukkit"));
    }

    @Test
    void domain_and_application_compiled_main_classes_have_no_edge_dependencies() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferencesInMainClasses())
            .isEmpty();
    }

    @SuppressWarnings("unused")
    private static final class DeliberatelyViolatingFixture {
        private Server server;
    }

    private static final class BytecodeReferenceScanner {
        private static final List<ForbiddenPrefix> FORBIDDEN_PREFIXES = List.of(
            new ForbiddenPrefix("org/bukkit", "org.bukkit"),
            new ForbiddenPrefix("io/papermc", "io.papermc"),
            new ForbiddenPrefix("net/milkbowl", "net.milkbowl"),
            new ForbiddenPrefix("cn/blockeco/exchange/infrastructure/", ".infrastructure.")
        );

        private BytecodeReferenceScanner() {
        }

        static List<String> findForbiddenReferences(Class<?> type) throws IOException {
            String resourceName = type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new IOException("Cannot locate compiled class resource " + resourceName);
                }
                return findForbiddenReferences(input.readAllBytes(), type.getName());
            }
        }

        static List<String> findForbiddenReferencesInMainClasses() throws IOException, URISyntaxException {
            Path classRoot = Path.of(cn.blockeco.exchange.BlockecoPlugin.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> violations = new ArrayList<>();
            try (var paths = Files.walk(classRoot)) {
                paths.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> {
                        String normalizedPath = path.toString().replace('\\', '/');
                        return normalizedPath.contains("/domain/") || normalizedPath.contains("/application/");
                    })
                    .forEach(path -> {
                        try {
                            violations.addAll(findForbiddenReferences(Files.readAllBytes(path), classRoot.relativize(path).toString()));
                        } catch (IOException exception) {
                            throw new IllegalStateException("Cannot inspect " + path, exception);
                        }
                    });
            }
            return violations;
        }

        private static List<String> findForbiddenReferences(byte[] classBytes, String className) {
            String bytecode = new String(classBytes, StandardCharsets.ISO_8859_1);
            return FORBIDDEN_PREFIXES.stream()
                .filter(prefix -> bytecode.contains(prefix.bytecodePrefix()))
                .map(prefix -> className + " references " + prefix.displayName())
                .toList();
        }

        private record ForbiddenPrefix(String bytecodePrefix, String displayName) {
        }
    }
}
