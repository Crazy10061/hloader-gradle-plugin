package com.hloader.gradle.tasks;

import com.hloader.gradle.VersionResolver;
import com.hloader.gradle.preprocess.CodePreprocessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Rewrites {@code //? if <condition> { ... //? }} and {@code //$$}-prefixed regions in a Java
 * source tree for the active {@code minecraftVersion}, so one shared source tree can target
 * several Minecraft versions without duplicating files. See {@link CodePreprocessor} for the
 * comment/condition syntax. Non-{@code .java} files are copied through unchanged.
 */
public abstract class SourcePreprocessor extends DefaultTask {

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getMinecraftVersion();

    @TaskAction
    public void preprocess() throws IOException {
        Path outputRoot = getOutputDir().get().getAsFile().toPath();
        getProject().delete(outputRoot);
        Files.createDirectories(outputRoot);

        if (!getSourceDir().isPresent()) {
            return;
        }
        Path sourceRoot = getSourceDir().get().getAsFile().toPath();
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }

        String activeVersion = VersionResolver.resolveVersionId(getMinecraftVersion().get());

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                Path relative = sourceRoot.relativize(path);
                Path destination = outputRoot.resolve(relative.toString());
                Files.createDirectories(destination.getParent());

                if (path.toString().endsWith(".java")) {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    String processed;
                    try {
                        processed = CodePreprocessor.process(source, activeVersion);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Failed to preprocess " + relative + ": " + e.getMessage(), e);
                    }
                    Files.writeString(destination, processed, StandardCharsets.UTF_8);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
