package com.hloader.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Talks to Mojang's public version manifest to resolve version metadata for either game side. */
public final class VersionResolver {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /**
     * Versions old enough to boot through this exact class (roughly pre-1.6) hit a long list of
     * known-bad interactions with modern Java/LWJGL that Mojang's original LaunchWrapper never
     * accounted for. MCPHackers' fork (github.com/MCPHackers/LaunchWrapper) is a drop-in
     * replacement - same class name, published at the coordinates below - that fixes them; see
     * {@link #withMcpHackersLaunchWrapper}.
     */
    private static final String LAUNCHWRAPPER_MAIN_CLASS = "net.minecraft.launchwrapper.Launch";
    private static final String MCPHACKERS_LAUNCHWRAPPER_VERSION = "1.3.0";
    private static final String MCPHACKERS_MAVEN = "https://maven.glass-launcher.net/releases/";
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    /** MCPHackers' LaunchWrapper's own runtime dependencies (its published POM lists none, but its classes reference these directly - see its build.gradle). */
    private static final String ASM_VERSION = "9.10.1";
    private static final String JSON_VERSION = "20250517";

    /**
     * LWJGL 2 and this exact jinput build predate Apple Silicon and were never given an arm64
     * macOS build by their original authors, so Mojang's manifest has no "osx-arm64" entry for
     * them at all. These are the same community rebuilds PrismLauncher's own meta service
     * (meta.prismlauncher.org) substitutes for the same reason - not Mojang-hosted, but a
     * well-established, widely-used source for exactly this problem.
     */
    private static final Map<String, String> APPLE_SILICON_NATIVE_OVERRIDES = Map.of(
            "org.lwjgl.lwjgl:lwjgl-platform",
            "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar",
            "net.java.jinput:jinput-platform",
            "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar"
    );

    /**
     * The manifest is the same ~1MB JSON regardless of which side/version is being resolved, and
     * a single build routinely resolves it more than once (at least once per
     * {@code DownloadMinecraftJar} instance - server and client both need it). Caching it for the
     * life of the JVM (the Gradle daemon, in practice) avoids re-fetching it needlessly; staleness
     * isn't a practical concern since the manifest only grows (new versions get appended, existing
     * ones are never rewritten) and a fresh daemon picks up anything new anyway.
     */
    private static volatile JsonObject cachedManifest;

    /** Same rationale as {@link #cachedManifest}: derived from it, so cached alongside it for the life of the JVM. */
    private static volatile List<String> cachedChronologicalVersionIds;

    private VersionResolver() {
    }

    private static JsonObject manifest() {
        JsonObject local = cachedManifest;
        if (local == null) {
            synchronized (VersionResolver.class) {
                local = cachedManifest;
                if (local == null) {
                    local = JsonParser.parseString(fetch(MANIFEST_URL)).getAsJsonObject();
                    cachedManifest = local;
                }
            }
        }
        return local;
    }

    public static String resolveVersionId(String minecraftVersion) {
        if (!"latest".equals(minecraftVersion)) {
            return canonicalVersionId(minecraftVersion, manifest());
        }
        return manifest().getAsJsonObject("latest").get("release").getAsString();
    }

    /**
     * Mojang's ids for the oldest releases aren't what people (or other mapping projects) call
     * them: {@code 1.0} is often written {@code 1.0.0}, and there is no plain {@code 1.2} at all,
     * only {@code 1.2.1}-{@code 1.2.5}. Accepts those spellings and returns Mojang's own id -
     * unknown ids are returned unchanged, so the caller's "unknown version" error still fires.
     */
    static String canonicalVersionId(String requested, JsonObject manifest) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            ids.add(element.getAsJsonObject().get("id").getAsString());
        }
        if (ids.contains(requested)) {
            return requested;
        }
        for (String alias : equivalentVersionIds(requested)) {
            if (ids.contains(alias)) {
                return alias;
            }
        }
        // "1.2" -> the first "1.2.x" release (the manifest lists newest first).
        if (requested.matches("\\d+\\.\\d+")) {
            String first = null;
            for (String id : ids) {
                if (id.matches(Pattern.quote(requested) + "\\.\\d+")) {
                    first = id;
                }
            }
            if (first != null) {
                return first;
            }
        }
        return requested;
    }

    /** {@code 1.0} &lt;-&gt; {@code 1.0.0}: the same version with a trailing {@code .0} added or removed. */
    public static List<String> equivalentVersionIds(String versionId) {
        List<String> aliases = new ArrayList<>();
        if (versionId.matches("\\d+\\.\\d+")) {
            aliases.add(versionId + ".0");
        } else if (versionId.matches("\\d+\\.\\d+\\.0")) {
            aliases.add(versionId.substring(0, versionId.length() - 2));
        }
        return aliases;
    }

    /**
     * All known version ids (releases, snapshots, and legacy pre-release ids like
     * {@code c0.0.13a_03} or {@code rd-132211}) ordered oldest to newest, by Mojang's manifest
     * {@code releaseTime}. These legacy ids aren't semver-sortable, so release time - not the id
     * string itself - is what {@link #compareVersions} relies on for ordering.
     */
    public static List<String> versionIdsChronological() {
        List<String> local = cachedChronologicalVersionIds;
        if (local == null) {
            synchronized (VersionResolver.class) {
                local = cachedChronologicalVersionIds;
                if (local == null) {
                    record Entry(String id, Instant releaseTime) {
                    }
                    List<Entry> entries = new ArrayList<>();
                    for (JsonElement element : manifest().getAsJsonArray("versions")) {
                        JsonObject entry = element.getAsJsonObject();
                        entries.add(new Entry(entry.get("id").getAsString(),
                                OffsetDateTime.parse(entry.get("releaseTime").getAsString()).toInstant()));
                    }
                    entries.sort(Comparator.comparing(Entry::releaseTime));
                    local = entries.stream().map(Entry::id).toList();
                    cachedChronologicalVersionIds = local;
                }
            }
        }
        return local;
    }

    /**
     * Orders two version ids the way Stonecutter-style {@code //? if} conditions need: negative if
     * {@code a} released before {@code b}, positive if after, zero if the same id. Both must be
     * known ids from Mojang's manifest (resolve {@code "latest"} via {@link #resolveVersionId}
     * first - this doesn't accept it).
     */
    public static int compareVersions(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        List<String> order = versionIdsChronological();
        int indexA = order.indexOf(a);
        int indexB = order.indexOf(b);
        if (indexA < 0) {
            throw new IllegalArgumentException("Unknown Minecraft version: " + a);
        }
        if (indexB < 0) {
            throw new IllegalArgumentException("Unknown Minecraft version: " + b);
        }
        return Integer.compare(indexA, indexB);
    }

    /** {@code side} is {@code "client"} or {@code "server"}. */
    public static MinecraftVersionInfo fetchVersionInfo(String minecraftVersion, String side, boolean patchLegacyLaunchWrapper) {
        JsonObject manifest = manifest();
        String versionId = "latest".equals(minecraftVersion)
                ? manifest.getAsJsonObject("latest").get("release").getAsString()
                : canonicalVersionId(minecraftVersion, manifest);

        String versionUrl = null;
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(versionId)) {
                versionUrl = entry.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IllegalArgumentException("Unknown Minecraft version: " + versionId
                    + suggestionSuffix(versionId, manifest));
        }

        JsonObject versionJson = JsonParser.parseString(fetch(versionUrl)).getAsJsonObject();
        String mainClass = versionJson.get("mainClass").getAsString();
        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        JsonObject sideDownload = downloads == null ? null : downloads.getAsJsonObject(side);
        boolean dedicatedServer = true;
        if (sideDownload == null) {
            // Versions from before Mojang started hosting a dedicated, hash-addressable server jar
            // (roughly pre-1.2.4, e.g. 1.0) have no "server" entry in the manifest at all - the
            // client jar is the closest available substitute for compiling against.
            if ("server".equals(side) && downloads != null && downloads.has("client")) {
                sideDownload = downloads.getAsJsonObject("client");
                dedicatedServer = false;
            } else {
                throw new IllegalStateException(
                        "Minecraft " + versionId + " has no \"" + side + "\" download in Mojang's manifest.");
            }
        }
        String downloadUrl = sideDownload.get("url").getAsString();

        String assetIndexId = null;
        String assetIndexUrl = null;
        if (versionJson.has("assetIndex")) {
            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            assetIndexId = assetIndex.get("id").getAsString();
            assetIndexUrl = assetIndex.get("url").getAsString();
        }

        String mappingsKey = side + "_mappings";
        String mappingsUrl = downloads != null && downloads.has(mappingsKey)
                ? downloads.getAsJsonObject(mappingsKey).get("url").getAsString() : null;

        List<LibraryInfo> libraries = new ArrayList<>();
        if (versionJson.has("libraries")) {
            JsonArray libraryArray = versionJson.getAsJsonArray("libraries");
            Set<String> allLibraryNames = new HashSet<>();
            for (JsonElement element : libraryArray) {
                allLibraryNames.add(element.getAsJsonObject().get("name").getAsString());
            }
            for (JsonElement element : libraryArray) {
                libraries.addAll(toLibraryInfos(element.getAsJsonObject(), allLibraryNames));
            }
        }
        if (patchLegacyLaunchWrapper && LAUNCHWRAPPER_MAIN_CLASS.equals(mainClass)) {
            libraries = withMcpHackersLaunchWrapper(libraries);
        }
        libraries = withAppleSiliconNarratorFix(libraries);

        return new MinecraftVersionInfo(versionId, mainClass, downloadUrl, assetIndexId, assetIndexUrl, mappingsUrl, libraries, dedicatedServer);
    }

    /**
     * Versions old enough to predate Apple Silicon (roughly pre-1.19) pull in {@code jna:4.x} and
     * {@code java-objc-bridge:1.0.0} for the client's narrator/accessibility support - both bundle
     * native libraries built before arm64 Macs existed (fat binaries covering only i386/x86_64), so
     * the client crashes with {@code UnsatisfiedLinkError} the moment the narrator is touched (which
     * happens unconditionally very early in client startup, not just when narrator is actually
     * enabled). Mojang itself has long since moved on to {@code jna:5.17.0} and
     * {@code java-objc-bridge:1.1} - both drop-in replacements (same classes JNA/the objc bridge
     * consumers use, e.g. {@code com.sun.jna.Native}, {@code ca.weblite.objc.Proxy}) whose bundled
     * natives are universal x86_64+arm64 binaries - so this just brings an old version's copy of
     * those two libraries forward to what a current one already ships, the same way
     * {@link #withMcpHackersLaunchWrapper} brings LaunchWrapper forward.
     */
    private static List<LibraryInfo> withAppleSiliconNarratorFix(List<LibraryInfo> original) {
        if (!isAppleSiliconMac()) {
            return original;
        }
        List<LibraryInfo> patched = new ArrayList<>();
        boolean sawOldJna = false;
        boolean sawOldObjcBridge = false;
        for (LibraryInfo library : original) {
            if (library.path().matches("net/java/dev/jna/jna/[1-4]\\..*")) {
                sawOldJna = true;
            } else if (library.path().startsWith("ca/weblite/java-objc-bridge/1.0.0/")) {
                sawOldObjcBridge = true;
            } else {
                patched.add(library);
            }
        }
        if (sawOldJna) {
            patched.add(mavenLibrary(MAVEN_CENTRAL, "net/java/dev/jna/jna", "5.17.0", "jna"));
        }
        if (sawOldObjcBridge) {
            patched.add(mavenLibrary(MAVEN_CENTRAL, "ca/weblite/java-objc-bridge", "1.1", "java-objc-bridge"));
        }
        return patched;
    }

    /**
     * Drops the vanilla {@code net.minecraft:launchwrapper} entry (identified by its Maven path,
     * the way every other library in this list is addressed) and adds MCPHackers' replacement
     * plus its own runtime dependencies in its place. Only ever called for versions whose
     * {@code mainClass} is exactly {@code net.minecraft.launchwrapper.Launch} - every other
     * version's library list passes through this method untouched.
     *
     * <p>Also drops any {@code org.ow2.asm:*} entry already in the vanilla list (e.g. 1.0
     * declares {@code asm-all:4.1}, a ~2012-era ASM that was LaunchWrapper 1.5's own transitive
     * dependency, flattened into Mojang's manifest). Left in place, that ancient jar ends up on
     * the classpath alongside the modern ASM MCPHackers' LaunchWrapper needs; since both define
     * the same {@code org.objectweb.asm.ClassReader} class, whichever wins classpath ordering can
     * shadow the other, and the ancient one can't parse a modern JDK's class files.</p>
     */
    private static List<LibraryInfo> withMcpHackersLaunchWrapper(List<LibraryInfo> original) {
        List<LibraryInfo> patched = new ArrayList<>();
        for (LibraryInfo library : original) {
            if (!library.path().startsWith("net/minecraft/launchwrapper/")
                    && !library.path().startsWith("org/ow2/asm/")) {
                patched.add(library);
            }
        }
        patched.add(mavenLibrary(MCPHACKERS_MAVEN, "org/mcphackers/launchwrapper", MCPHACKERS_LAUNCHWRAPPER_VERSION, "launchwrapper"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm", ASM_VERSION, "asm"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm-tree", ASM_VERSION, "asm-tree"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/ow2/asm/asm-commons", ASM_VERSION, "asm-commons"));
        patched.add(mavenLibrary(MAVEN_CENTRAL, "org/json/json", JSON_VERSION, "json"));
        return patched;
    }

    private static LibraryInfo mavenLibrary(String repoBaseUrl, String groupAndArtifactPath, String version, String artifactId) {
        String path = groupAndArtifactPath + "/" + version + "/" + artifactId + "-" + version + ".jar";
        return new LibraryInfo(repoBaseUrl + path, path, false);
    }

    /**
     * A single library entry can contribute up to two downloads: the regular jar (modern versions
     * use {@code downloads.artifact}; natives are a separate library with a {@code :natives-<os>}
     * name suffix) and, on old (pre-1.13-ish) versions, a natives jar declared via a top-level
     * {@code natives} map pointing into {@code downloads.classifiers} instead.
     */
    private static List<LibraryInfo> toLibraryInfos(JsonObject library, Set<String> allLibraryNames) {
        List<LibraryInfo> result = new ArrayList<>();
        String name = library.get("name").getAsString();
        if (!isAllowedOnCurrentOs(library) || !matchesCurrentArch(name, allLibraryNames)) {
            return result;
        }
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads == null) {
            return result;
        }

        if (downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            boolean isNative = library.get("name").getAsString().contains(":natives-");
            LibraryInfo javaJarOverride = appleSiliconLwjglJavaJarOverride(name);
            result.add(javaJarOverride != null ? javaJarOverride
                    : new LibraryInfo(artifact.get("url").getAsString(), artifact.get("path").getAsString(), isNative));
        }

        if (library.has("natives") && downloads.has("classifiers")) {
            JsonObject natives = library.getAsJsonObject("natives");
            String libraryName = library.get("name").getAsString();
            String overrideUrl = isAppleSiliconMac() && !natives.has("osx-arm64") ? appleSiliconOverrideUrl(libraryName) : null;

            if (overrideUrl != null) {
                String fileName = libraryName.replace(':', '-') + "-natives-osx-arm64.jar";
                result.add(new LibraryInfo(overrideUrl, "hloader-apple-silicon-overrides/" + fileName, true));
            } else {
                String classifierKey = natives.has(currentOsName()) ? natives.get(currentOsName()).getAsString() : null;
                if (classifierKey != null) {
                    // Old-format classifier keys can contain "${arch}" (e.g. "natives-windows-${arch}").
                    classifierKey = classifierKey.replace("${arch}", System.getProperty("sun.arch.data.model", "64"));
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(classifierKey)) {
                        JsonObject classifier = classifiers.getAsJsonObject(classifierKey);
                        result.add(new LibraryInfo(classifier.get("url").getAsString(), classifier.get("path").getAsString(), true));
                    }
                }
            }
        }

        return result;
    }

    /**
     * The Apple Silicon native override (see {@link #APPLE_SILICON_NATIVE_OVERRIDES}) substitutes
     * a rebuild based on LWJGL 2.9.4-nightly's native ABI. Versions that declare {@code lwjgl:2.9.0}
     * (e.g. {@code 1.0}, {@code rd-132211}) then pair 2.9.0-vintage Java classes against that much
     * newer native library - an ABI mismatch that crashes natively inside a JNI callback
     * ({@code jni_CallVoidMethod}) the moment LWJGL creates its window, rather than throwing a
     * catchable Java exception. {@code 1.7.10} declares {@code lwjgl:2.9.1} and never hits this.
     * Bumping the Java-side jar to the same 2.9.1 whenever the native override is in play closes
     * that gap without touching versions that already avoid it.
     */
    private static LibraryInfo appleSiliconLwjglJavaJarOverride(String libraryName) {
        // Only 2.9.0 is confirmed to have this ABI mismatch (see 1.0, rd-132211) - later 2.9.x
        // point releases are closer to (or past) the native override's own 2.9.4-nightly base and
        // don't need bumping, so this only touches the one version actually known to be broken.
        if (!isAppleSiliconMac()) {
            return null;
        }
        if (libraryName.equals("org.lwjgl.lwjgl:lwjgl:2.9.0")) {
            return mavenLibrary(MAVEN_CENTRAL, "org/lwjgl/lwjgl/lwjgl", "2.9.1", "lwjgl");
        }
        if (libraryName.equals("org.lwjgl.lwjgl:lwjgl_util:2.9.0")) {
            return mavenLibrary(MAVEN_CENTRAL, "org/lwjgl/lwjgl/lwjgl_util", "2.9.1", "lwjgl_util");
        }
        return null;
    }

    private static String appleSiliconOverrideUrl(String libraryName) {
        for (Map.Entry<String, String> entry : APPLE_SILICON_NATIVE_OVERRIDES.entrySet()) {
            if (libraryName.startsWith(entry.getKey() + ":")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isAppleSiliconMac() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isMac = osName.contains("mac") || osName.contains("darwin");
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");
        return isMac && isArm;
    }

    /**
     * Modern (LWJGL 3.x-era) manifests list a separate library entry per architecture for the
     * same natives, distinguished only by a {@code -arm64} suffix on the classifier (e.g.
     * {@code org.lwjgl:lwjgl-sdl:3.4.3:natives-windows} alongside
     * {@code ...:natives-windows-arm64}) - both entries' {@code rules} just say "windows", with
     * no {@code arch} field to filter on. Downloading and extracting both onto the same
     * {@code -natives} directory means two same-named DLLs race to occupy the same file, and
     * whichever extracts last wins; on a non-ARM host that can silently leave the wrong-arch
     * (arm64) native in place, which then fails to load with a generic "invalid Win32
     * application"-style error. Skip the variant that doesn't match the current JVM's
     * architecture whenever a matching counterpart for the *other* architecture exists.
     */
    private static boolean matchesCurrentArch(String libraryName, Set<String> allLibraryNames) {
        boolean hostIsArm64 = isArm64();
        boolean nameIsArm64 = libraryName.endsWith("-arm64");
        if (nameIsArm64) {
            return hostIsArm64;
        }
        return !hostIsArm64 || !allLibraryNames.contains(libraryName + "-arm64");
    }

    private static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    private static boolean isAllowedOnCurrentOs(JsonObject library) {
        if (!library.has("rules")) {
            return true;
        }

        String currentOs = currentOsName();
        boolean allowed = false;
        for (JsonElement element : library.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !os.get("name").getAsString().equals(currentOs)) {
                    matches = false;
                }
                // Some old versions (e.g. rd-132211) restrict a library to a specific OS *version*
                // too (e.g. only osx 10.5.x) - without checking this, that library incorrectly gets
                // included on every OS version, colliding with the one meant for modern systems.
                if (matches && os.has("version")
                        && !Pattern.compile(os.get("version").getAsString()).matcher(System.getProperty("os.version", "")).find()) {
                    matches = false;
                }
            }
            if (matches) {
                allowed = "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    private static String currentOsName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    /**
     * Suggests close matches for a version id that wasn't found - e.g. {@code rd-122211} (typo)
     * suggesting {@code rd-132211}, or {@code 1.0} vs {@code 1.0.0} confusion. Only offers
     * suggestions within a small edit-distance budget (scaled to the input's length) so a
     * genuinely unrelated id doesn't get padded out with irrelevant noise.
     */
    private static String suggestionSuffix(String versionId, JsonObject manifest) {
        int budget = Math.max(2, versionId.length() / 4);
        List<String> suggestions = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            String candidate = element.getAsJsonObject().get("id").getAsString();
            if (levenshtein(versionId, candidate, budget) <= budget) {
                suggestions.add(candidate);
            }
        }
        if (suggestions.isEmpty()) {
            return ".";
        }
        suggestions.sort((a, b) -> levenshtein(versionId, a, budget) - levenshtein(versionId, b, budget));
        return " - did you mean: " + String.join(", ", suggestions.subList(0, Math.min(5, suggestions.size()))) + "?";
    }

    /** Classic edit-distance DP, capped early once a row exceeds {@code maxDistance} (this is called once per manifest entry, so bailing out early matters). */
    private static int levenshtein(String a, String b, int maxDistance) {
        if (Math.abs(a.length() - b.length()) > maxDistance) {
            return maxDistance + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > maxDistance) {
                return maxDistance + 1;
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[b.length()];
    }

    /** Returns the response body, or {@code null} on a 404 (used for "does this even exist" probes). */
    public static byte[] fetchBytesOrNull(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch " + url, e);
        }
    }

    /** Returns the response body, or {@code null} on a 404 (used for "does this even exist" probes). */
    public static String fetchOrNull(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch " + url, e);
        }
    }

    public static String fetch(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch " + url, e);
        }
    }
}
