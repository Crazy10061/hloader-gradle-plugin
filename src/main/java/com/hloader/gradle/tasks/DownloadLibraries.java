package com.hloader.gradle.tasks;

import com.hloader.gradle.MinecraftVersionInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** Downloads a version's (OS-filtered) library jars and extracts any native (.so/.dll/.dylib) libraries out of them. */
public abstract class DownloadLibraries extends DefaultTask {

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    /**
     * {@code getVersionInfo()} is {@code @Internal} (resolving it means a network call, which
     * Gradle shouldn't trigger just to snapshot task inputs), but that leaves this task with no
     * tracked inputs at all - since {@link #getLibrariesDir()} isn't version-specific, Gradle
     * then considers it up-to-date forever after the first run, even after switching Minecraft
     * versions to one needing an entirely different set of libraries. Tracking the resolved
     * library paths here (still lazy, still only evaluated when Gradle actually needs to check
     * up-to-date-ness) gives it a real, changing input to key off instead.
     */
    @Input
    public abstract ListProperty<String> getLibraryPaths();

    @OutputDirectory
    public abstract DirectoryProperty getLibrariesDir();

    @OutputDirectory
    public abstract DirectoryProperty getNativesDir();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        MinecraftVersionInfo info = getVersionInfo().get();
        File librariesDir = getLibrariesDir().get().getAsFile();
        File nativesDir = getNativesDir().get().getAsFile();
        Files.createDirectories(librariesDir.toPath());
        Files.createDirectories(nativesDir.toPath());

        AtomicInteger done = new AtomicInteger();
        int total = info.libraries().size();
        // Gradle's ProgressLogger only ever renders on the ephemeral rich-console status line -
        // invisible with --console=plain, in most CI logs, and in some IDE-embedded consoles.
        // Plain getLogger().lifecycle() calls always show up, so progress is reported that way
        // instead, at 10% steps.
        AtomicInteger lastLoggedPercent = new AtomicInteger(-1);

        getLogger().lifecycle("hloader: downloading libraries (0/" + total + ")");

        // i dont know how i came up with this, i just copy pasted some old code lol
        // - mangodev1
        int threads = (Runtime.getRuntime().availableProcessors() + 2) / 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);


        List<Callable<Void>> tasks = info.libraries().stream()
            .map(lib -> (Callable<Void>)() -> {
                try {
                    File dest = new File(librariesDir, lib.path());
                    if (!dest.exists()) {
                        Files.createDirectories(dest.getParentFile().toPath());
                        downloadWithRetry(lib.url(), dest);
                    }

                    if (lib.isNative()) {
                        extractNatives(dest, nativesDir);
                    }

                    int completedCount = done.incrementAndGet();
                    int percent = total == 0 ? 100 : completedCount * 100 / total;
                    int previous = lastLoggedPercent.get();
                    if (percent >= previous + 10 && lastLoggedPercent.compareAndSet(previous, percent)) {
                        getLogger().lifecycle("hloader: libraries " + percent + "% (" + completedCount + "/" + total + ")");
                    }
                } catch (IOException e) {
                    getLogger().lifecycle("hloader: error downloading library: " + e.getMessage());
                }

                return null;
            })
            .toList();

        pool.invokeAll(tasks);
        pool.shutdown();
        pool.close();

        getLogger().lifecycle("hloader: " + total + " libraries ready in " + librariesDir);
    }

    /**
     * Copying straight into {@code dest} left two problems: a download interrupted partway
     * through a transient failure (several parallel downloads hitting the same GitHub raw-content
     * host, which occasionally resets connections under concurrent load) left a corrupt partial
     * file behind that {@code dest.exists()} would then skip re-downloading forever; and there was
     * no retry at all for exactly that kind of transient failure. Downloading to a temp file and
     * atomically renaming it into place fixes the first; a couple of retries fixes the second.
     */
    private static void downloadWithRetry(String url, File dest) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            File tempDest = File.createTempFile(dest.getName(), ".tmp", dest.getParentFile());
            try {
                try (InputStream in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, tempDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tempDest.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tempDest.toPath());
            }
        }
        throw last;
    }

    private void extractNatives(File jarFile, File nativesDir) throws IOException {
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || !isNativeLibraryFile(name)) {
                    continue;
                }
                // Several library entries can independently extract into the same nativesDir at
                // once (this whole method runs from a thread pool) and can even target the exact
                // same destination filename - writing in place risks one thread reading a
                // partially-overwritten file, or macOS refusing the overwrite outright ("Resource
                // busy") if another thread has the old copy open. Writing to a temp file first and
                // atomically renaming it into place avoids both.
                File out = new File(nativesDir, new File(name).getName());
                File tempOut = File.createTempFile(out.getName(), ".tmp", nativesDir);
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, tempOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tempOut.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private static boolean isNativeLibraryFile(String name) {
        return name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".jnilib");
    }
}
