package com.hloader.gradle.tasks;

import com.hloader.gradle.mapping.JarMerger;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
@DisableCachingByDefault(because = "Merges jars into a shared directory")

/** Merges the server-side and client-side game jars into one compile-time jar. See {@link JarMerger}. */
public abstract class MergeGameJars extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getServerGameJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getClientJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void merge() throws IOException {
        JarMerger.merge(getServerGameJar().get().getAsFile(), getClientJar().get().getAsFile(), getOutputJar().get().getAsFile());
        getLogger().lifecycle("hloader: merged client+server game jars to " + getOutputJar().get().getAsFile());
    }
}
