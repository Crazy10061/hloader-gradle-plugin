package com.hloader.gradle.tasks;

import com.hloader.gradle.LibraryInfo;
import com.hloader.gradle.MinecraftVersionInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/**
 * Builds the mod, drops it into a "mods" folder, and launches a downloaded Minecraft client with
 * hloader attached as a {@code -javaagent}. Offline/cracked-style login (no real Microsoft
 * account) so it's usable for quick manual testing without setting up auth.
 */
public abstract class RunDevClient extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getLoaderJar();

    @InputFile
    public abstract RegularFileProperty getClientJar();

    @InputFile
    public abstract RegularFileProperty getModJar();

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    @InputDirectory
    public abstract DirectoryProperty getLibrariesDir();

    @InputDirectory
    public abstract DirectoryProperty getNativesDir();

    @InputDirectory
    public abstract DirectoryProperty getAssetsDir();

    @OutputDirectory
    public abstract DirectoryProperty getRunDir();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void run() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();

        Path runDir = getRunDir().get().getAsFile().toPath();
        Path modsDir = runDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path modJar = getModJar().get().getAsFile().toPath();
        Files.copy(modJar, modsDir.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        File librariesDir = getLibrariesDir().get().getAsFile();
        List<Object> classpath = new ArrayList<>();
        classpath.add(getClientJar().get().getAsFile());
        for (LibraryInfo library : info.libraries()) {
            if (!library.isNative()) {
                classpath.add(new File(librariesDir, library.path()));
            }
        }

        String uuid = UUID.randomUUID().toString().replace("-", "");

        getExecOperations().javaexec(spec -> {
            spec.classpath(classpath);
            spec.jvmArgs("-javaagent:" + getLoaderJar().get().getAsFile().getPath());
            spec.jvmArgs("-Djava.library.path=" + getNativesDir().get().getAsFile().getPath());
            spec.jvmArgs("-Dhloader.side=CLIENT");
            spec.jvmArgs("-Dhloader.minecraftVersion=" + info.versionId());
            if (needsFirstThread(info) && isMac()) {
                // Modern Minecraft's windowing needs the JVM's actual first OS thread on macOS, or
                // it fails with "Unable to initialize SDL: No available video device" even with a
                // real display attached. Older, AWT/LWJGL2-based versions manage the main thread
                // themselves and can hang if this is forced on them, so it's only added when the
                // version's own libraries pull in LWJGL3's windowing module - GLFW pre-Mojang's
                // switch to SDL3 (roughly 25w-era snapshots onward), lwjgl-sdl since.
                spec.jvmArgs("-XstartOnFirstThread");
            }

            Package pkg = getClass().getPackage();
            spec.jvmArgs("-Dminecraft.launcher.brand=" + pkg.getName());
            spec.jvmArgs("-Dminecraft.launcher.version=" + pkg.getImplementationVersion());

            spec.getMainClass().set(info.mainClass());
            spec.args(
                    "--username", "Dev",
                    "--version", info.versionId(),
                    "--gameDir", runDir.toString(),
                    "--assetsDir", getAssetsDir().get().getAsFile().getPath(),
                    "--assetIndex", info.assetIndexId(),
                    "--uuid", uuid,
                    "--accessToken", "0",
                    "--userType", "legacy",
                    "--versionType", "release",
                    // Older (pre-1.8-ish) client mains mark this as a *required* joptsimple option
                    // even though nothing meaningful reads it today - an empty JSON object is what
                    // the vanilla launcher itself sends when there's nothing to report.
                    "--userProperties", "{}");
            spec.setWorkingDir(runDir.toFile());
            spec.setIgnoreExitValue(true);
        });
    }

    private static boolean needsFirstThread(MinecraftVersionInfo info) {
        return info.libraries().stream().anyMatch(lib -> lib.path().contains("lwjgl-glfw") || lib.path().contains("lwjgl-sdl"));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
