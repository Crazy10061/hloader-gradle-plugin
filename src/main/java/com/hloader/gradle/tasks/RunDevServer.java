package com.hloader.gradle.tasks;

import com.hloader.gradle.MinecraftVersionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/**
 * Builds the mod, drops it into a "mods" folder, and runs a downloaded Minecraft server jar with
 * hloader attached as a {@code -javaagent} - no jar-patching needed for local dev runs, since we
 * control the launch command ourselves.
 */
public abstract class RunDevServer extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getLoaderJar();

    @InputFile
    public abstract RegularFileProperty getServerJar();

    @InputFile
    public abstract RegularFileProperty getModJar();

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    @OutputDirectory
    public abstract DirectoryProperty getRunDir();

    @Input
    public abstract Property<Boolean> getExportMixins();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void run() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();
        if (!info.dedicatedServer()) {
            throw new IllegalStateException("Minecraft " + info.versionId() + " has no dedicated server release "
                    + "(no \"server\" download in Mojang's manifest) - runDevServer isn't available for this "
                    + "version. " + info.versionId() + "'s client jar is used as a compile-time substitute "
                    + "elsewhere, but it has no runnable server entry point.");
        }

        Path runDir = getRunDir().get().getAsFile().toPath();
        Path modsDir = runDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path modJar = getModJar().get().getAsFile().toPath();
        Files.copy(modJar, modsDir.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        getExecOperations().exec(spec -> {
            spec.setWorkingDir(runDir.toFile());
            spec.commandLine("java");
            spec.args("-javaagent:" + getLoaderJar().get().getAsFile().getPath());
            spec.args("-Dhloader.side=SERVER");
            spec.args("-Dhloader.minecraftVersion=" + info.versionId());
            if (Boolean.TRUE.equals(getExportMixins().getOrElse(false))) {
                spec.args("-Dmixin.debug.export=true");
            }
            spec.args("-jar", getServerJar().get().getAsFile().getPath());
            spec.setIgnoreExitValue(true);
        });
    }
}
