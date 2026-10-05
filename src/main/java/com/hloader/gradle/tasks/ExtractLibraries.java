package com.hloader.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Pulls the game jar's own library jars out of a bundler-format (1.18+) server.jar, so javac can
 * resolve its type annotations. Older server jars don't ship a separate library list (they're
 * mostly self-contained), so this just produces an empty directory for those.
 */
public abstract class ExtractLibraries extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @OutputDirectory
    public abstract DirectoryProperty getLibrariesDir();

    @TaskAction
    public void extract() throws IOException {
        File input = getServerJar().get().getAsFile();
        File outputDir = getLibrariesDir().get().getAsFile();
        Files.createDirectories(outputDir.toPath());
        try (ZipFile zip = new ZipFile(input)) {
            ZipEntry listEntry = zip.getEntry("META-INF/libraries.list");
            if (listEntry == null) {
                getLogger().lifecycle("hloader: " + input + " isn't bundler-format, no separate libraries to extract");
                return;
            }
            String listText;
            try (InputStream in = zip.getInputStream(listEntry)) {
                listText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String line : listText.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String path = line.split("\t")[2].trim();
                ZipEntry jarEntry = zip.getEntry("META-INF/libraries/" + path);
                if (jarEntry == null) {
                    throw new IllegalStateException(input + " doesn't contain META-INF/libraries/" + path);
                }
                File outFile = new File(outputDir, path.substring(path.lastIndexOf('/') + 1));
                try (InputStream in = zip.getInputStream(jarEntry)) {
                    Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        getLogger().lifecycle("hloader: extracted libraries to " + outputDir);
    }
}
