package com.hloader.gradle.tasks;

import com.hloader.gradle.VersionResolver;
import com.hloader.gradle.preprocess.CodePreprocessor;
import com.hloader.gradle.preprocess.MixinConfigPreprocessor;
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
 * Version-gates the two resource formats hloader knows how to preprocess for the active
 * {@code minecraftVersion}: {@code .cfg} access-transformer files, via {@link CodePreprocessor}'s
 * {@code //? if}/{@code //$$} comment syntax (its parser already treats those as harmless no-ops
 * for lines it can't read as a rule), and {@code *.mixins.json} mixin configs, via
 * {@link MixinConfigPreprocessor}'s JSON-native conditional entry shape ({@code //} comments
 * aren't valid JSON, so that one needs a different syntax). Every other resource is copied through
 * unchanged - notably a plain {@code .json} file that isn't a mixin config never has this applied.
 */
public abstract class ResourcePreprocessor extends DefaultTask {

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
                String fileName = path.getFileName().toString();

                if (fileName.endsWith(".mixins.json")) {
                    rewrite(path, destination, relative, activeVersion, MixinConfigPreprocessor::process);
                } else if (fileName.endsWith(".cfg")) {
                    rewrite(path, destination, relative, activeVersion, CodePreprocessor::process);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    @FunctionalInterface
    private interface Transform {
        String apply(String source, String activeVersion);
    }

    private static void rewrite(Path source, Path destination, Path relative, String activeVersion, Transform transform) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String processed;
        try {
            processed = transform.apply(text, activeVersion);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to preprocess " + relative + ": " + e.getMessage(), e);
        }
        Files.writeString(destination, processed, StandardCharsets.UTF_8);
    }
}
