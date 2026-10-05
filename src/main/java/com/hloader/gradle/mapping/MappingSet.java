package com.hloader.gradle.mapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pre-computed, both-directions view of a {@link ProguardMappings}, with everything in binary
 * (slash-separated) form and obfuscated-side method/field descriptors derived - what both the
 * compile-time deobfuscating remapper and the SRG writer need.
 */
public final class MappingSet {

    record MethodEntry(String obfOwner, String obfName, String obfDescriptor, String officialName, String officialDescriptor) {
    }

    record FieldEntry(String obfOwner, String obfName, String officialName) {
    }

    public final Map<String, String> obfToOfficialClass = new HashMap<>();
    public final Map<String, String> officialToObfClass = new HashMap<>();
    public final Map<String, MethodEntry> methodsByObfKey = new HashMap<>();
    public final Map<String, FieldEntry> fieldsByObfKey = new HashMap<>();

    public static MappingSet from(ProguardMappings mappings) {
        MappingSet set = new MappingSet();

        mappings.officialToObfClass().forEach((official, obf) -> {
            String officialBinary = official.replace('.', '/');
            set.officialToObfClass.put(officialBinary, obf);
            set.obfToOfficialClass.put(obf, officialBinary);
        });

        mappings.officialToObfMethod().forEach((key, obfName) -> {
            String officialOwnerBinary = key.officialOwner().replace('.', '/');
            String obfOwner = set.officialToObfClass.getOrDefault(officialOwnerBinary, officialOwnerBinary);
            String obfDescriptor = DescriptorUtil.remapDescriptor(key.officialDescriptor(), set.officialToObfClass);
            MethodEntry entry = new MethodEntry(obfOwner, obfName, obfDescriptor, key.officialName(), key.officialDescriptor());
            set.methodsByObfKey.put(methodKey(obfOwner, obfName, obfDescriptor), entry);
        });

        mappings.officialToObfField().forEach((key, obfName) -> {
            String officialOwnerBinary = key.officialOwner().replace('.', '/');
            String obfOwner = set.officialToObfClass.getOrDefault(officialOwnerBinary, officialOwnerBinary);
            set.fieldsByObfKey.put(fieldKey(obfOwner, obfName), new FieldEntry(obfOwner, obfName, key.officialName()));
        });

        return set;
    }

    /**
     * Mapping sources only list members that actually got renamed. Real SRG consumers (Mixin's
     * annotation processor included) need an explicit entry for every member a mixin could
     * possibly reference, even unchanged ones - so this reads every class in the actual game jar
     * and fills in identity entries (obf name == named name) for anything not already mapped.
     */
    public void completeFromJar(File jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                completeFromClass(data);
            }
        }
    }

    private void completeFromClass(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        String obfOwner = reader.getClassName();
        obfToOfficialClass.putIfAbsent(obfOwner, obfOwner);
        officialToObfClass.putIfAbsent(obfToOfficialClass.get(obfOwner), obfOwner);

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methodsByObfKey.putIfAbsent(methodKey(obfOwner, name, descriptor),
                        new MethodEntry(obfOwner, name, descriptor, name, descriptor));
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fieldsByObfKey.putIfAbsent(fieldKey(obfOwner, name), new FieldEntry(obfOwner, name, name));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    static String methodKey(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String fieldKey(String owner, String name) {
        return owner + "." + name;
    }
}
