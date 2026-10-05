package com.hloader.gradle.mapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Rewrites every class in a jar from obfuscated names to Mojang's official (readable) names. */
public final class JarRemapper {

    private JarRemapper() {
    }

    public static void remap(File input, File output, MappingSet mappings) throws IOException {
        Remapper remapper = new DeobfuscatingRemapper(mappings);
        Files.createDirectories(output.toPath().getParent());

        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }

                String outName = entry.getName();
                if (outName.endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    data = writer.toByteArray();
                    outName = remapper.map(outName.substring(0, outName.length() - 6)) + ".class";
                }

                out.putNextEntry(new ZipEntry(outName));
                out.write(data);
                out.closeEntry();
            }
        }
    }

    private static final class DeobfuscatingRemapper extends Remapper {

        private final MappingSet mappings;

        DeobfuscatingRemapper(MappingSet mappings) {
            this.mappings = mappings;
        }

        @Override
        public String map(String internalName) {
            return mappings.obfToOfficialClass.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                return name;
            }
            MappingSet.MethodEntry entry = mappings.methodsByObfKey.get(MappingSet.methodKey(owner, name, descriptor));
            return entry != null ? entry.officialName() : name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            MappingSet.FieldEntry entry = mappings.fieldsByObfKey.get(MappingSet.fieldKey(owner, name));
            return entry != null ? entry.officialName() : name;
        }
    }
}
