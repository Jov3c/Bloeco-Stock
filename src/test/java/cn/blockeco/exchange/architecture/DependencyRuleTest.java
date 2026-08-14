package cn.blockeco.exchange.architecture;

import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

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

        private static List<String> findForbiddenReferences(byte[] classBytes) {
            Set<String> references = new LinkedHashSet<>();
            new ClassReader(classBytes).accept(new ForbiddenTypeVisitor(references), 0);
            return List.copyOf(references);
        }

        private static final class ForbiddenTypeVisitor extends ClassVisitor {
            private final Set<String> references;
            private ForbiddenTypeVisitor(Set<String> references) { super(Opcodes.ASM9); this.references = references; }

            @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                recordInternalName(superName); if (interfaces != null) for (String anInterface : interfaces) recordInternalName(anInterface); recordSignature(signature);
            }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
            @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                recordDescriptor(descriptor); recordSignature(signature); recordValue(value);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                recordDescriptor(descriptor); recordSignature(signature); if (exceptions != null) for (String exception : exceptions) recordInternalName(exception);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) { recordDescriptor(descriptor); return annotationVisitor(); }
                    @Override public void visitTypeInsn(int opcode, String type) { recordInternalName(type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { recordInternalName(owner); recordDescriptor(descriptor); }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { recordInternalName(owner); recordDescriptor(descriptor); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) { recordDescriptor(descriptor); recordHandle(bootstrapMethodHandle); for (Object argument : bootstrapMethodArguments) recordValue(argument); }
                    @Override public void visitLdcInsn(Object value) { recordValue(value); }
                    @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { recordDescriptor(descriptor); }
                    @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) { recordInternalName(type); }
                    @Override public void visitLocalVariable(String name, String descriptor, String signature, org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) { recordDescriptor(descriptor); recordSignature(signature); }
                };
            }
            private AnnotationVisitor annotationVisitor() {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String name, Object value) { recordValue(value); }
                    @Override public void visitEnum(String name, String descriptor, String value) { recordDescriptor(descriptor); }
                    @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { recordDescriptor(descriptor); return annotationVisitor(); }
                    @Override public AnnotationVisitor visitArray(String name) { return annotationVisitor(); }
                };
            }
            private void recordHandle(Handle handle) { if (handle != null) { recordInternalName(handle.getOwner()); recordDescriptor(handle.getDesc()); } }
            private void recordValue(Object value) { if (value instanceof Type type) recordType(type); else if (value instanceof Handle handle) recordHandle(handle); }
            private void recordDescriptor(String descriptor) {
                if (descriptor == null) return;
                if (descriptor.startsWith("(")) { for (Type argument : Type.getArgumentTypes(descriptor)) recordType(argument); recordType(Type.getReturnType(descriptor)); }
                else recordType(Type.getType(descriptor));
            }
            private void recordSignature(String signature) { if (signature != null) new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) { @Override public void visitClassType(String name) { recordInternalName(name); } }); }
            private void recordType(Type type) { if (type.getSort() == Type.ARRAY) recordType(type.getElementType()); else if (type.getSort() == Type.OBJECT) recordInternalName(type.getInternalName()); else if (type.getSort() == Type.METHOD) recordDescriptor(type.getDescriptor()); }
            private void recordInternalName(String internalName) { if (internalName != null) FORBIDDEN_PREFIXES.stream().filter(prefix -> internalName.startsWith(prefix.internalNamePrefix())).map(ForbiddenPrefix::displayName).forEach(references::add); }
        }
        private record ForbiddenPrefix(String internalNamePrefix, String displayName) { }
    }
}
