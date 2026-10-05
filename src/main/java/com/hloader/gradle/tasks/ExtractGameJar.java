package com.hloader.gradle.tasks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Pulls the actual game jar out of server.jar into a single, self-contained compile-time jar.
 * Modern (1.18+) server jars use Mojang's "bundler" format, where the real game classes are packed
 * inside under META-INF/versions/; older server jars ARE the game jar directly. Either way, the
 * jar's own top-level classes (e.g. net.minecraft.bundler.Main on modern versions) are always
 * included too, so there's never a need for a second, overlapping jar on the compile classpath.
 */
public abstract class ExtractGameJar extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @OutputFile
    public abstract RegularFileProperty getGameJar();

    @TaskAction
    public void extract() throws IOException {
        File input = getServerJar().get().getAsFile();
        File output = getGameJar().get().getAsFile();
        Files.createDirectories(output.getParentFile().toPath());

        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output.toPath()))) {
            Set<String> written = new HashSet<>();

            Enumeration<? extends ZipEntry> topEntries = zip.entries();
            while (topEntries.hasMoreElements()) {
                ZipEntry entry = topEntries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/") || !written.add(entry.getName())) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }

            ZipEntry listEntry = zip.getEntry("META-INF/versions.list");
            if (listEntry == null) {
                getLogger().lifecycle("hloader: " + input + " isn't bundler-format, used directly as the game jar");
                return;
            }

            String firstLine;
            try (InputStream in = zip.getInputStream(listEntry)) {
                firstLine = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Empty versions.list in " + input));
            }
            String path = firstLine.split("\t")[2];
            ZipEntry nestedEntry = zip.getEntry("META-INF/versions/" + path);
            if (nestedEntry == null) {
                throw new IllegalStateException(input + " doesn't contain META-INF/versions/" + path);
            }

            byte[] nestedBytes;
            try (InputStream in = zip.getInputStream(nestedEntry)) {
                nestedBytes = in.readAllBytes();
            }
            try (ZipInputStream nestedZip = new ZipInputStream(new ByteArrayInputStream(nestedBytes))) {
                ZipEntry nestedFileEntry;
                while ((nestedFileEntry = nestedZip.getNextEntry()) != null) {
                    if (nestedFileEntry.isDirectory() || nestedFileEntry.getName().startsWith("META-INF/")
                            || !written.add(nestedFileEntry.getName())) {
                        continue;
                    }
                    out.putNextEntry(new ZipEntry(nestedFileEntry.getName()));
                    nestedZip.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        getLogger().lifecycle("hloader: extracted game jar to " + output);
    }
}
