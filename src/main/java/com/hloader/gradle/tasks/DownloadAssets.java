package com.hloader.gradle.tasks;

import com.hloader.gradle.MinecraftVersionInfo;
import com.hloader.gradle.VersionResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Downloads a version's asset index and every asset object it references, laid out the same way
 * the vanilla launcher does ({@code indexes/<id>.json}, {@code objects/<hash prefix>/<hash>}), so
 * {@code --assetsDir}/{@code --assetIndex} work as-is. This can be a genuinely large one-time
 * download (hundreds of MB); everything is cached by content hash afterward.
 */
public abstract class DownloadAssets extends DefaultTask {

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    /**
     * {@code getVersionInfo()} is {@code @Internal} (resolving it means a network call), which
     * would otherwise leave this task with no tracked inputs at all - since
     * {@link #getAssetsDir()} isn't version-specific, Gradle would then consider it up-to-date
     * forever after the first run, skipping the write of a *new* version's own
     * {@code indexes/<id>.json} after switching Minecraft versions. Individual asset objects are
     * still safe either way (content-addressed, checked with {@code if (!dest.exists())}), but
     * the index file itself needs this to always get (re)written when the asset index changes.
     */
    @Input
    public abstract Property<String> getAssetIndexId();

    @OutputDirectory
    public abstract DirectoryProperty getAssetsDir();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        MinecraftVersionInfo info = getVersionInfo().get();
        if (info.assetIndexUrl() == null) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no asset index, skipping");
            return;
        }

        File assetsDir = getAssetsDir().get().getAsFile();
        File indexesDir = new File(assetsDir, "indexes");
        File objectsDir = new File(assetsDir, "objects");
        Files.createDirectories(indexesDir.toPath());
        Files.createDirectories(objectsDir.toPath());

        String indexJson = VersionResolver.fetch(info.assetIndexUrl());
        Files.writeString(new File(indexesDir, info.assetIndexId() + ".json").toPath(), indexJson);

        JsonObject objects = JsonParser.parseString(indexJson).getAsJsonObject().getAsJsonObject("objects");

        int total = objects.size();
        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger downloaded = new AtomicInteger(0);
        // Gradle's ProgressLogger only ever renders on the ephemeral rich-console status line -
        // invisible with --console=plain, in most CI logs, and in some IDE-embedded consoles.
        // Plain getLogger().lifecycle() calls always show up, so progress is reported that way
        // instead, at 10% steps to avoid spamming a line per object across tens of thousands of
        // assets.
        AtomicInteger lastLoggedPercent = new AtomicInteger(-1);

        getLogger().lifecycle("hloader: downloading assets (0/" + total + ")");

        // i dont know how i came up with this, i just copy pasted some old code lol
        // - mangodev1
        int threads = (Runtime.getRuntime().availableProcessors() + 2) / 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = objects.entrySet().stream()
            .map(entry -> (Callable<Void>)() -> {
                try {
                    String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
                    String prefix = hash.substring(0, 2);
                    File dest = new File(objectsDir, prefix + "/" + hash);
                    if (!dest.exists()) {
                        Files.createDirectories(dest.getParentFile().toPath());
                        // Writing straight to dest would leave a corrupt partial file behind after
                        // a connection drop mid-download - dest.exists() would then skip
                        // re-downloading it forever. A temp file + atomic rename avoids that.
                        File tempDest = File.createTempFile(hash, ".tmp", dest.getParentFile());
                        try (InputStream in = URI.create("https://resources.download.minecraft.net/" + prefix + "/" + hash).toURL().openStream()) {
                            Files.copy(in, tempDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Files.move(tempDest.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } finally {
                            Files.deleteIfExists(tempDest.toPath());
                        }

                        downloaded.getAndIncrement();
                    }

                    int completedCount = done.incrementAndGet();
                    int percent = completedCount * 100 / total;
                    int previous = lastLoggedPercent.get();
                    if (percent >= previous + 10 && lastLoggedPercent.compareAndSet(previous, percent)) {
                        getLogger().lifecycle("hloader: assets " + percent + "% (" + completedCount + "/" + total + ")");
                    }
                } catch (Exception e) {
                    getLogger().lifecycle("hloader: error downloading asset: " + e.getMessage());
                }

                return null;
            })
            .toList();

        pool.invokeAll(tasks);
        pool.shutdown();
        pool.close();

        getLogger().lifecycle("hloader: assets ready (" + total + " objects, " + downloaded + " newly downloaded)");
    }
}
