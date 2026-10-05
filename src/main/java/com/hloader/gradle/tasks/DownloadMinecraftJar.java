package com.hloader.gradle.tasks;

import com.hloader.gradle.MinecraftVersionInfo;
import com.hloader.gradle.VersionResolver;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Downloads (and caches, keyed by resolved version id + side, under the Gradle user home) a Minecraft jar. */
public abstract class DownloadMinecraftJar extends DefaultTask {

    private MinecraftVersionInfo resolvedInfo;

    @Input
    public abstract Property<String> getMinecraftVersion();

    /** {@code "client"} or {@code "server"}. */
    @Input
    public abstract Property<String> getSide();

    @Input
    public abstract Property<Boolean> getPatchLegacyLaunchWrapper();

    @OutputFile
    public File getOutputJar() {
        return new File(getProject().getGradle().getGradleUserHomeDir(),
                "caches/hloader/" + resolvedInfo().versionId() + "/" + getSide().get() + ".jar");
    }

    @Internal
    public MinecraftVersionInfo getVersionInfo() {
        return resolvedInfo();
    }

    @TaskAction
    public void download() throws IOException {
        File output = getOutputJar();
        if (output.exists()) {
            getLogger().lifecycle("hloader: using cached Minecraft " + resolvedInfo().versionId() + " " + getSide().get() + ".jar at " + output);
            return;
        }
        getLogger().lifecycle("hloader: downloading Minecraft " + resolvedInfo().versionId() + " " + getSide().get()
                + ".jar from " + resolvedInfo().downloadUrl());
        Files.createDirectories(output.getParentFile().toPath());

        URLConnection connection = URI.create(resolvedInfo().downloadUrl()).toURL().openConnection();
        long totalBytes = connection.getContentLengthLong();
        File tempFile = new File(output.getParentFile(), output.getName() + ".part");
        byte[] buffer = new byte[1 << 16];
        long copied = 0;
        int lastLoggedPercent = -1;
        try (InputStream in = connection.getInputStream();
                OutputStream out = Files.newOutputStream(tempFile.toPath())) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                if (totalBytes > 0) {
                    int percent = (int) (copied * 100 / totalBytes);
                    if (percent >= lastLoggedPercent + 10) {
                        lastLoggedPercent = percent;
                        getLogger().lifecycle("hloader: " + getSide().get() + ".jar " + percent + "% ("
                                + (copied / 1_048_576) + "/" + (totalBytes / 1_048_576) + " MB)");
                    }
                }
            }
        }
        Files.move(tempFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        getLogger().lifecycle("hloader: " + getSide().get() + ".jar downloaded (" + (copied / 1_048_576) + " MB)");
    }

    private MinecraftVersionInfo resolvedInfo() {
        if (resolvedInfo == null) {
            resolvedInfo = VersionResolver.fetchVersionInfo(
                    getMinecraftVersion().get(), getSide().get(), getPatchLegacyLaunchWrapper().get());
        }
        return resolvedInfo;
    }
}
