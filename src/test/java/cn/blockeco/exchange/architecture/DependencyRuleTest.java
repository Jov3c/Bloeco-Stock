package cn.blockeco.exchange.architecture;

import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyRuleTest {
    @Test void reports_forbidden_references_in_a_compiled_violating_fixture() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(DeliberatelyViolatingFixture.class)).contains("org.bukkit");
    }

    @Test void ignores_an_ordinary_string_that_merely_looks_like_an_internal_name() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(StringOnlyFixture.class)).isEmpty();
    }

    @Test void detects_forbidden_types_in_descriptors_signatures_arrays_and_annotations() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(TypeReferenceFixture.class)).contains("org.bukkit");
    }

    @Test void detects_forbidden_code_type_annotation_descriptors() {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(codeTypeAnnotationFixture())).contains("org.bukkit");
    }

    @Test void detects_forbidden_sealed_permitted_subclass_metadata() {
        assertThat(BytecodeReferenceScanner.findForbiddenReferences(permittedSubclassFixture())).contains("org.bukkit");
    }

    @Test void domain_and_application_compiled_main_classes_have_no_edge_dependencies() throws Exception {
        assertThat(BytecodeReferenceScanner.findForbiddenReferencesInMainClasses()).isEmpty();
    }

    @SuppressWarnings("unused") private static final class DeliberatelyViolatingFixture { private Server server; }
    @SuppressWarnings("unused") private static final class StringOnlyFixture { private static final String LOOKS_LIKE_A_TYPE = "org/bukkit/Server"; }
    @BukkitTypeMarker(Server.class) @SuppressWarnings("unused") private static final class TypeReferenceFixture {
        @BukkitTypeMarker(Server.class) private Server field;
        private Server[] array;
        private List<? extends Server> generic;
        @BukkitTypeMarker(Server.class) private Server method(Server parameter, Server[] parameters) { return parameter; }
    }
    @Retention(RetentionPolicy.RUNTIME) private @interface BukkitTypeMarker { Class<?> value(); }

    private static byte[] codeTypeAnnotationFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/CodeTypeAnnotationFixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");
        method.visitInsnAnnotation(TypeReference.newTypeReference(TypeReference.CAST).getValue(), null, "Lorg/bukkit/Server;", true).visitEnd();
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] permittedSubclassFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "fixture/PermittedFixture", null, "java/lang/Object", null);
        writer.visitPermittedSubclass("org/bukkit/Server");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class BytecodeReferenceScanner {
        private static final List<ForbiddenPrefix> FORBIDDEN_PREFIXES = List.of(
            new ForbiddenPrefix("org/bukkit/", "org.bukkit"), new ForbiddenPrefix("io/papermc/", "io.papermc"),
            new ForbiddenPrefix("net/milkbowl/", "net.milkbowl"), new ForbiddenPrefix("cn/blockeco/exchange/infrastructure/", ".infrastructure."));
        private BytecodeReferenceScanner() { }

        static List<String> findForbiddenReferences(Class<?> type) throws IOException {
            String resourceName = type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getClassLoader().getResourceAsStream(resourceName)) {
                if (input == null) throw new IOException("Cannot locate compiled class resource " + resourceName);
                return findForbiddenReferences(input.readAllBytes());
            }
        }

        static List<String> findForbiddenReferencesInMainClasses() throws IOException, URISyntaxException {
            Path classRoot = Path.of(cn.blockeco.exchange.BlockecoPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> violations = new ArrayList<>();
            try (var paths = Files.walk(classRoot)) {
                paths.filter(path -> path.toString().endsWith(".class")).filter(path -> {
                    String normalized = path.toString().replace('\\', '/');
                    return normalized.contains("/domain/") || normalized.contains("/application/");
                }).forEach(path -> {
                    try { violations.addAll(findForbiddenReferences(Files.readAllBytes(path))); }
                    catch (IOException exception) { throw new IllegalStateException("Cannot inspect " + path, exception); }
                });
            }
            return violations;
        }

        static List<String> findForbiddenReferences(byte[] classBytes) {
            Set<String> references = new LinkedHashSet<>();
            new ClassReader(classBytes).accept(new ClassRemapper(new ClassWriter(0), new RecordingRemapper(references)), 0);
            return List.copyOf(references);
        }

        private static final class RecordingRemapper extends Remapper {
            private final Set<String> references;
            private RecordingRemapper(Set<String> references) { this.references = references; }

            @Override public String map(String internalName) {
                if (internalName != null) FORBIDDEN_PREFIXES.stream().filter(prefix -> internalName.startsWith(prefix.internalNamePrefix())).map(ForbiddenPrefix::displayName).forEach(references::add);
                return internalName;
            }
        }
        private record ForbiddenPrefix(String internalNamePrefix, String displayName) { }
    }
}
