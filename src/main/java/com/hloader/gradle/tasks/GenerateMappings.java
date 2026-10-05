package com.hloader.gradle.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hloader.gradle.MinecraftVersionInfo;
import com.hloader.gradle.VersionResolver;
import com.hloader.gradle.mapping.LegacyFabricMappings;
import com.hloader.gradle.mapping.MappingSet;
import com.hloader.gradle.mapping.McpNames;
import com.hloader.gradle.mapping.OrnitheMappings;
import com.hloader.gradle.mapping.ProguardMappings;
import com.hloader.gradle.mapping.SrgParser;
import com.hloader.gradle.mapping.SrgWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Downloads mapping data for a version and writes it as an SRG file for {@link RemapGameJar} and
 * Mixin's annotation processor.
 *
 * <p>By default ({@code hloader.mappingProvider} = {@code "auto"}), sources are tried in order:
 * Mojang's own official (Proguard-format) mappings (roughly 1.14.4+); Forge/MCP's
 * {@code joined.srg} (SRG intermediate names, 1.6.4+) with {@code mcp_stable}'s CSV data layered
 * on top for real human names when available; Legacy Fabric's {@code intermediary}+{@code yarn}
 * (roughly 1.3.2-1.13.2) as an alternative human-name source when MCP's isn't available for this
 * version. Set {@code hloader.mappingProvider} to force one specific source instead - see
 * {@link com.hloader.gradle.HloaderExtension#getMappingProvider()}. Versions with none of the
 * above get an empty (no-op) mapping.</p>
 */
public abstract class GenerateMappings extends DefaultTask {

    private static final String MCP_SRG_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/%s/mcp-%s-srg.zip";
    private static final String MCP_STABLE_METADATA_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/maven-metadata.xml";
    private static final String MCP_STABLE_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/%s/mcp_stable-%s.zip";

    private static final String LEGACY_FABRIC_META = "https://meta.legacyfabric.net/v2/versions/";
    private static final String LEGACY_FABRIC_MAVEN = "https://repo.legacyfabric.net/legacyfabric/";

    private static final String ORNITHE_META = "https://meta.ornithemc.net/v3/versions/";
    private static final String ORNITHE_MAVEN = "https://maven.ornithemc.net/releases/";

    private MappingSet mappingSet;

    @Internal
    public abstract Property<MinecraftVersionInfo> getVersionInfo();

    /** Optional: when present and it also has official mappings, those get merged in too - see {@link MergeGameJars}. */
    @Internal
    public abstract Property<MinecraftVersionInfo> getClientVersionInfo();

    @InputFile
    public abstract RegularFileProperty getGameJar();

    @Input
    @Optional
    public abstract Property<String> getMcpMappingVersion();

    /** {@code "auto"}, {@code "mojang"}, {@code "mcp"}, {@code "legacy-fabric"}, or {@code "none"}. */
    @Input
    public abstract Property<String> getMappingProvider();

    @OutputFile
    public abstract RegularFileProperty getSrgFile();

    /** Same mapping data as {@link #getSrgFile()}, but with columns reversed - see {@link SrgWriter#writeReversed}. */
    @OutputFile
    public abstract RegularFileProperty getReobfSrgFile();

    @TaskAction
    public void generate() throws IOException {
        MinecraftVersionInfo info = getVersionInfo().get();
        String provider = getMappingProvider().getOrElse("auto");

        mappingSet = switch (provider) {
            case "auto" -> resolveAuto(info);
            case "mojang" -> requireMojang(info);
            case "mcp" -> requireMcp(info.versionId());
            case "legacy-fabric" -> requireLegacyFabric(info.versionId());
            case "ornithe" -> requireOrnithe(info.versionId());
            case "none" -> MappingSet.from(ProguardMappings.empty());
            default -> throw new IllegalArgumentException("Unknown hloader.mappingProvider \"" + provider
                    + "\" - expected one of: auto, mojang, mcp, legacy-fabric, ornithe, none");
        };

        // Mojang's and Forge/MCP's mapping data only lists members that actually got renamed - real
        // SRG consumers like Mixin's annotation processor need an explicit entry for EVERY member a
        // mixin might reference, even ones that keep their original name. This reads every class in
        // the actual game jar and fills in identity entries for anything not already mapped.
        mappingSet.completeFromJar(getGameJar().get().getAsFile());

        SrgWriter.write(mappingSet, getSrgFile().get().getAsFile());
        SrgWriter.writeReversed(mappingSet, getReobfSrgFile().get().getAsFile());
        getLogger().lifecycle("hloader: wrote completed mappings to " + getSrgFile().get().getAsFile());
    }

    private MappingSet resolveAuto(MinecraftVersionInfo info) throws IOException {
        if (info.mappingsUrl() != null) {
            return officialMappings(info);
        }

        MappingSet mcpMappings = tryForgeMcpMappings(info.versionId());
        boolean mcpHasHumanNames = false;
        if (mcpMappings != null) {
            mcpHasHumanNames = applyMcpNamesIfAvailable(mcpMappings, info.versionId());
        }
        if (mcpMappings != null && mcpHasHumanNames) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no official Mojang mappings - using Forge/MCP's "
                    + "joined.srg + mcp_stable instead");
            return mcpMappings;
        }

        MappingSet ornithe = tryOrnitheMappings(info.versionId());
        if (ornithe != null) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no official Mojang mappings and no mcp_stable "
                    + "human names - using OrnitheMC's feather mappings instead");
            return ornithe;
        }

        MappingSet legacyFabric = tryLegacyFabricMappings(info.versionId());
        if (legacyFabric != null) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no official Mojang mappings, no mcp_stable "
                    + "human names, and no OrnitheMC feather mapping - using Legacy Fabric's intermediary+yarn instead");
            return legacyFabric;
        }

        if (mcpMappings != null) {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no official Mojang mappings, no mcp_stable human "
                    + "names, and no OrnitheMC or Legacy Fabric mapping either - keeping raw Forge/MCP SRG names");
            return mcpMappings;
        }

        if (isAlreadyUnobfuscated(getGameJar().get().getAsFile())) {
            // Newer (year.month scheme, e.g. 26.x) game jars ship real class names directly -
            // no mapping is published because there's nothing left to map.
            getLogger().lifecycle("hloader: " + info.versionId() + " ships an already-deobfuscated game jar - no mapping needed");
        } else {
            getLogger().lifecycle("hloader: " + info.versionId() + " has no mapping source available from any "
                    + "provider this task knows about - skipping deobfuscation");
        }
        return MappingSet.from(ProguardMappings.empty());
    }

    private MappingSet requireMojang(MinecraftVersionInfo info) throws IOException {
        if (info.mappingsUrl() == null) {
            throw new IllegalStateException("hloader.mappingProvider is \"mojang\", but Minecraft " + info.versionId()
                    + " has no official Mojang mappings (only available roughly 1.14.4+).");
        }
        return officialMappings(info);
    }

    private MappingSet officialMappings(MinecraftVersionInfo info) throws IOException {
        String mappingText = VersionResolver.fetch(info.mappingsUrl());
        MappingSet set = MappingSet.from(ProguardMappings.parse(mappingText));
        getLogger().lifecycle("hloader: generated mappings for " + info.versionId() + " from Mojang's official mappings ("
                + set.obfToOfficialClass.size() + " classes)");

        // The game jar is a client+server merge (see MergeGameJars) but mappings are published
        // per side - fold the client mapping in too for client-only classes/members.
        MinecraftVersionInfo clientInfo = getClientVersionInfo().getOrNull();
        if (clientInfo != null && clientInfo.mappingsUrl() != null) {
            String clientMappingText = VersionResolver.fetch(clientInfo.mappingsUrl());
            MappingSet clientMappingSet = MappingSet.from(ProguardMappings.parse(clientMappingText));
            set.obfToOfficialClass.putAll(clientMappingSet.obfToOfficialClass);
            set.officialToObfClass.putAll(clientMappingSet.officialToObfClass);
            set.methodsByObfKey.putAll(clientMappingSet.methodsByObfKey);
            set.fieldsByObfKey.putAll(clientMappingSet.fieldsByObfKey);
            getLogger().lifecycle("hloader: merged in client mappings too (" + clientMappingSet.obfToOfficialClass.size() + " classes)");
        }
        return set;
    }

    private MappingSet requireMcp(String versionId) throws IOException {
        MappingSet mappings = tryForgeMcpMappings(versionId);
        if (mappings == null) {
            throw new IllegalStateException("hloader.mappingProvider is \"mcp\", but Forge/MCP has no joined.srg "
                    + "mapping for Minecraft " + versionId + " (only available 1.6.4+).");
        }
        boolean hasHumanNames = applyMcpNamesIfAvailable(mappings, versionId);
        if (!hasHumanNames) {
            getLogger().lifecycle("hloader: no mcp_stable human names for " + versionId + " - keeping raw SRG names");
        }
        return mappings;
    }

    private MappingSet requireLegacyFabric(String versionId) throws IOException {
        MappingSet mappings = tryLegacyFabricMappings(versionId);
        if (mappings == null) {
            throw new IllegalStateException("hloader.mappingProvider is \"legacy-fabric\", but Legacy Fabric has no "
                    + "intermediary+yarn mapping for Minecraft " + versionId + " (only available roughly 1.3.2-1.13.2 - "
                    + "see https://github.com/Legacy-Fabric).");
        }
        return mappings;
    }

    private MappingSet requireOrnithe(String versionId) throws IOException {
        MappingSet mappings = tryOrnitheMappings(versionId);
        if (mappings == null) {
            throw new IllegalStateException("hloader.mappingProvider is \"ornithe\", but OrnitheMC has no feather "
                    + "mapping for Minecraft " + versionId + " (only available roughly c0.0.12a_03-1.14.4 - "
                    + "see https://github.com/OrnitheMC).");
        }
        return mappings;
    }

    /** @return true if real human names were actually applied (false if only raw SRG names remain). */
    private boolean applyMcpNamesIfAvailable(MappingSet mappings, String versionId) throws IOException {
        String mcpVersion = resolveMcpStableVersion(versionId);
        if (mcpVersion == null) {
            return false;
        }
        String url = MCP_STABLE_URL.formatted(mcpVersion, mcpVersion);
        byte[] zipBytes = VersionResolver.fetchBytesOrNull(url);
        if (zipBytes == null) {
            return false;
        }
        McpNames.applyFromZip(mappings, zipBytes);
        getLogger().lifecycle("hloader: applied mcp_stable " + mcpVersion + " human names");
        return true;
    }

    /** Returns {@code <revision>-<versionId>}, either from the configured revision or the newest published one. */
    private String resolveMcpStableVersion(String versionId) throws IOException {
        String configured = getMcpMappingVersion().getOrNull();
        if (configured != null && !configured.isBlank()) {
            return configured.contains("-") ? configured : configured + "-" + versionId;
        }

        String metadata = VersionResolver.fetch(MCP_STABLE_METADATA_URL);
        Pattern pattern = Pattern.compile("<version>(\\d+)-" + Pattern.quote(versionId) + "</version>");
        Matcher matcher = pattern.matcher(metadata);
        int best = -1;
        while (matcher.find()) {
            best = Math.max(best, Integer.parseInt(matcher.group(1)));
        }
        return best < 0 ? null : best + "-" + versionId;
    }

    /**
     * A jar that already has real, dotted-package class names (rather than short obfuscated ones)
     * needs no deobfuscation at all. Checking for one specific well-known class isn't reliable on
     * its own: even genuinely-obfuscated jars from the applet era keep a couple of outer classes
     * (e.g. {@code net.minecraft.client.Minecraft}, {@code MinecraftApplet}) unobfuscated so
     * embedding code has a stable class to instantiate, while every other class - including all
     * the actual game logic - is still a short, default-package obfuscated name. Instead, this
     * checks what fraction of ALL classes are in the default package (no {@code /} in their
     * name) - legacy obfuscation dumps the vast majority of classes there, so a jar is only
     * genuinely unobfuscated if that fraction is negligible.
     */
    private static boolean isAlreadyUnobfuscated(File gameJar) throws IOException {
        int total = 0;
        int defaultPackage = 0;
        try (ZipFile zip = new ZipFile(gameJar)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                total++;
                if (!name.contains("/")) {
                    defaultPackage++;
                }
            }
        }
        return total > 0 && defaultPackage * 10 < total;
    }

    private static MappingSet tryForgeMcpMappings(String versionId) throws IOException {
        String url = MCP_SRG_URL.formatted(versionId, versionId);
        byte[] zipBytes = VersionResolver.fetchBytesOrNull(url);
        if (zipBytes == null) {
            return null;
        }

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("joined.srg")) {
                    String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    return SrgParser.parse(text);
                }
            }
        }
        return null;
    }

    /**
     * Queries Legacy Fabric's meta API for the newest published {@code intermediary} and
     * {@code yarn} build for this exact Minecraft version, downloads both jars, and composes
     * them. Returns {@code null} if either is unavailable (outside their covered range, roughly
     * 1.3.2-1.13.2).
     */
    private static MappingSet tryLegacyFabricMappings(String versionId) throws IOException {
        for (String alias : VersionResolver.equivalentVersionIds(versionId)) {
            MappingSet mappings = tryLegacyFabricMappingsExact(alias);
            if (mappings != null) {
                return mappings;
            }
        }
        return tryLegacyFabricMappingsExact(versionId);
    }

    private static MappingSet tryLegacyFabricMappingsExact(String versionId) throws IOException {
        String encodedVersion = URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        String intermediaryVersion = newestLegacyFabricVersion(LEGACY_FABRIC_META + "intermediary/" + encodedVersion);
        String yarnVersion = newestLegacyFabricVersion(LEGACY_FABRIC_META + "yarn/" + encodedVersion);
        if (intermediaryVersion == null || yarnVersion == null) {
            return null;
        }

        byte[] intermediaryJar = VersionResolver.fetchBytesOrNull(
                LEGACY_FABRIC_MAVEN + "net/legacyfabric/intermediary/" + intermediaryVersion
                        + "/intermediary-" + intermediaryVersion + ".jar");
        byte[] yarnJar = VersionResolver.fetchBytesOrNull(
                LEGACY_FABRIC_MAVEN + "net/legacyfabric/yarn/" + yarnVersion + "/yarn-" + yarnVersion + "-v2.jar");
        if (intermediaryJar == null || yarnJar == null) {
            return null;
        }

        String intermediaryTiny = readZipEntry(intermediaryJar, "mappings/mappings.tiny");
        String yarnTiny = readZipEntry(yarnJar, "mappings/mappings.tiny");
        if (intermediaryTiny == null || yarnTiny == null) {
            return null;
        }
        return LegacyFabricMappings.compose(intermediaryTiny, yarnTiny);
    }

    /** The meta API lists builds newest-first; returns its {@code "version"} field, or null if the version isn't covered. */
    private static String newestLegacyFabricVersion(String metaUrl) throws IOException {
        String json = VersionResolver.fetchOrNull(metaUrl);
        if (json == null) {
            return null;
        }
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        JsonObject first = array.get(0).getAsJsonObject();
        return first.get("version").getAsString();
    }

    /**
     * Queries OrnitheMC's meta API for the newest published {@code feather} build for this exact
     * Minecraft version and downloads+parses it - a single jar already carries the full
     * obfuscated-&gt;named chain (see {@link OrnitheMappings}), no composition needed. Returns
     * {@code null} if unavailable (outside its covered range, roughly c0.0.12a_03-1.14.4).
     */
    /**
     * Ornithe names some of the oldest releases differently from Mojang ({@code 1.0.0} for
     * Mojang's {@code 1.0}) - without trying both, 1.0 silently got no mappings at all and the
     * "deobfuscated" compile jar stayed fully obfuscated.
     */
    private static MappingSet tryOrnitheMappings(String versionId) throws IOException {
        MappingSet mappings = tryOrnitheMappingsExact(versionId);
        if (mappings != null) {
            return mappings;
        }
        for (String alias : VersionResolver.equivalentVersionIds(versionId)) {
            mappings = tryOrnitheMappingsExact(alias);
            if (mappings != null) {
                return mappings;
            }
        }
        return null;
    }

    private static MappingSet tryOrnitheMappingsExact(String versionId) throws IOException {
        String encodedVersion = URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        String json = VersionResolver.fetchOrNull(ORNITHE_META + "feather/" + encodedVersion);
        if (json == null) {
            return null;
        }
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        String mavenCoordinate = array.get(0).getAsJsonObject().get("maven").getAsString();

        byte[] featherJar = VersionResolver.fetchBytesOrNull(ORNITHE_MAVEN + mavenCoordinateToPath(mavenCoordinate));
        if (featherJar == null) {
            return null;
        }
        String featherTiny = readZipEntry(featherJar, "mappings/mappings.tiny");
        if (featherTiny == null) {
            return null;
        }
        return OrnitheMappings.parse(featherTiny);
    }

    /** {@code "group.id:artifact:version"} -&gt; {@code "group/id/artifact/version/artifact-version.jar"}. */
    private static String mavenCoordinateToPath(String coordinate) {
        String[] parts = coordinate.split(":");
        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }

    private static String readZipEntry(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
