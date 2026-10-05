package com.hloader.gradle.mapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Merges a "primary" and a "secondary" game jar into one compile-time jar covering both sides'
 * classes and members - mirroring what Forge/MCP's own "joined" mapping (used by
 * {@code joined.srg}) already assumes a single, merged view of the game exists. Members that exist
 * on only one side get added to the merged class with a trivial stub body on the other; real
 * execution always happens against the actual, unmerged runtime bytecode for whichever side is
 * actually running, so a stub is never invoked - it only needs to exist so a mod's {@code @Mixin}/
 * {@code @Shadow} references to either side's members compile in one place.
 */
public final class JarMerger {

    private JarMerger() {
    }

    public static void merge(File primary, File secondary, File output) throws IOException {
        Map<String, byte[]> primaryClasses = readClasses(primary);
        Map<String, byte[]> secondaryClasses = readClasses(secondary);

        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(primaryClasses.keySet());
        allNames.addAll(secondaryClasses.keySet());

        Files.createDirectories(output.getParentFile().toPath());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            for (String name : allNames) {
                byte[] primaryBytes = primaryClasses.get(name);
                byte[] secondaryBytes = secondaryClasses.get(name);
                byte[] merged = (primaryBytes != null && secondaryBytes != null)
                        ? mergeClass(primaryBytes, secondaryBytes)
                        : (primaryBytes != null ? primaryBytes : secondaryBytes);
                out.putNextEntry(new ZipEntry(name + ".class"));
                out.write(merged);
                out.closeEntry();
            }
        }
    }

    private static Map<String, byte[]> readClasses(File jar) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    classes.put(entry.getName().substring(0, entry.getName().length() - 6), in.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static byte[] mergeClass(byte[] primaryBytes, byte[] secondaryBytes) {
        ClassNode primaryNode = new ClassNode();
        new ClassReader(primaryBytes).accept(primaryNode, ClassReader.SKIP_FRAMES);
        ClassNode secondaryNode = new ClassNode();
        new ClassReader(secondaryBytes).accept(secondaryNode, ClassReader.SKIP_FRAMES);

        Set<String> existingMethods = new HashSet<>();
        for (MethodNode m : primaryNode.methods) {
            existingMethods.add(m.name + m.desc);
        }
        for (MethodNode m : secondaryNode.methods) {
            // A stubbed <init>/<clinit> would need a real super(...)/this(...) call to verify -
            // safer to just not merge in a side-only constructor than risk synthesizing an invalid
            // one. A class present on only one side entirely still gets included as-is, unaffected.
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) {
                continue;
            }
            if (existingMethods.add(m.name + m.desc)) {
                primaryNode.methods.add(stubMethod(m));
            }
        }

        Set<String> existingFields = new HashSet<>();
        for (FieldNode f : primaryNode.fields) {
            existingFields.add(f.name);
        }
        for (FieldNode f : secondaryNode.fields) {
            if (existingFields.add(f.name)) {
                primaryNode.fields.add(f);
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        primaryNode.accept(writer);
        return writer.toByteArray();
    }

    /** A trivial body with the same signature - never actually executed, just needed to compile. */
    private static MethodNode stubMethod(MethodNode original) {
        MethodNode stub = new MethodNode(
                original.access,
                original.name,
                original.desc,
                original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(new String[0]));

        boolean hasBody = (original.access & Opcodes.ACC_ABSTRACT) == 0 && (original.access & Opcodes.ACC_NATIVE) == 0;
        if (hasBody) {
            InsnList insns = stub.instructions;
            switch (Type.getReturnType(original.desc).getSort()) {
                case Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new InsnNode(Opcodes.IRETURN));
                }
                case Type.LONG -> {
                    insns.add(new InsnNode(Opcodes.LCONST_0));
                    insns.add(new InsnNode(Opcodes.LRETURN));
                }
                case Type.FLOAT -> {
                    insns.add(new InsnNode(Opcodes.FCONST_0));
                    insns.add(new InsnNode(Opcodes.FRETURN));
                }
                case Type.DOUBLE -> {
                    insns.add(new InsnNode(Opcodes.DCONST_0));
                    insns.add(new InsnNode(Opcodes.DRETURN));
                }
                default -> {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new InsnNode(Opcodes.ARETURN));
                }
            }
            stub.maxStack = 2;
            stub.maxLocals = 4;
        }
        return stub;
    }
}
