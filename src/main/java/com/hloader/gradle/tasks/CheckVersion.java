package com.hloader.gradle.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.hloader.gradle.MinecraftVersionInfo;
import com.hloader.gradle.VersionResolver;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Prints a report of what hloader knows about the configured {@code minecraftVersion}: whether it
 * has a dedicated server, whether the LaunchWrapper patch applies, and which mapping source each
 * of {@link GenerateMappings}' providers would find - checking each one's actual availability
 * directly (a metadata/existence probe), without downloading and parsing the full mapping data the
 * way {@link GenerateMappings} itself needs to.
 */
public abstract class CheckVersion extends DefaultTask {

    private static final String MCP_SRG_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/%s/mcp-%s-srg.zip";
    private static final String MCP_STABLE_METADATA_URL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/maven-metadata.xml";
    private static final String LEGACY_FABRIC_META = "https://meta.legacyfabric.net/v2/versions/";
    private static final String ORNITHE_META = "https://meta.ornithemc.net/v3/versions/";

    @Internal
    public abstract Property<MinecraftVersionInfo> getServerVersionInfo();

    @Internal
    public abstract Property<MinecraftVersionInfo> getClientVersionInfo();

    @Input
    public abstract Property<Boolean> getPatchLegacyLaunchWrapper();

    @Input
    public abstract Property<String> getMappingProviderSetting();

    @TaskAction
    public void check() throws IOException {
        MinecraftVersionInfo server = getServerVersionInfo().get();
        MinecraftVersionInfo client = getClientVersionInfo().get();
        String versionId = client.versionId();

        StringBuilder report = new StringBuilder();
        report.append("\nhloader: version check for ").append(versionId).append('\n');
        report.append("  mainClass: ").append(client.mainClass()).append('\n');
        report.append("  dedicated server: ").append(server.dedicatedServer()
                ? "yes" : "no (runDevServer will fail - server side falls back to the client jar as a compile-time substitute only)").append('\n');

        boolean usesLaunchWrapper = "net.minecraft.launchwrapper.Launch".equals(client.mainClass());
        if (usesLaunchWrapper) {
            report.append("  boots via LaunchWrapper: yes (").append(getPatchLegacyLaunchWrapper().get()
                    ? "MCPHackers' compatibility patch is active" : "patch is DISABLED - vanilla Java 9+/LWJGL bugs apply")
                    .append(")\n");
        } else {
            report.append("  boots via LaunchWrapper: no\n");
        }

        report.append("  mapping sources:\n");
        boolean mojang = client.mappingsUrl() != null;
        report.append("    mojang (official)   : ").append(mojang ? "available" : "not available").append('\n');

        boolean mcpSrg = VersionResolver.fetchBytesOrNull(MCP_SRG_URL.formatted(versionId, versionId)) != null;
        String mcpStableVersion = mcpSrg ? resolveMcpStableVersion(versionId) : null;
        report.append("    mcp (joined.srg)    : ").append(!mcpSrg ? "not available"
                : mcpStableVersion != null ? "available (+ mcp_stable " + mcpStableVersion + " human names)"
                : "available (SRG names only - no mcp_stable human names)").append('\n');

        String ornitheVersion = newestMetaVersion(ORNITHE_META + "feather/" + encode(versionId));
        report.append("    ornithe (feather)   : ").append(ornitheVersion != null ? "available (" + ornitheVersion + ")" : "not available").append('\n');

        String legacyFabricIntermediary = newestMetaVersion(LEGACY_FABRIC_META + "intermediary/" + encode(versionId));
        String legacyFabricYarn = newestMetaVersion(LEGACY_FABRIC_META + "yarn/" + encode(versionId));
        boolean legacyFabric = legacyFabricIntermediary != null && legacyFabricYarn != null;
        report.append("    legacy-fabric (yarn): ").append(legacyFabric ? "available (" + legacyFabricYarn + ")" : "not available").append('\n');

        String autoWinner = mojang ? "mojang"
                : mcpStableVersion != null ? "mcp"
                : ornitheVersion != null ? "ornithe"
                : legacyFabric ? "legacy-fabric"
                : mcpSrg ? "mcp (raw SRG names only)"
                : "none (raw obfuscated names)";
        report.append("  hloader.mappingProvider=\"").append(getMappingProviderSetting().get())
                .append("\" resolves to: ").append("auto".equals(getMappingProviderSetting().get()) ? autoWinner : getMappingProviderSetting().get())
                .append('\n');

        getLogger().lifecycle(report.toString());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The meta API lists builds newest-first; returns its {@code "version"} field, or null if unavailable. */
    private static String newestMetaVersion(String metaUrl) throws IOException {
        String json = VersionResolver.fetchOrNull(metaUrl);
        if (json == null) {
            return null;
        }
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        return array.get(0).getAsJsonObject().get("version").getAsString();
    }

    /** Returns {@code <revision>-<versionId>} for the newest published mcp_stable revision, or null if none exists. */
    private static String resolveMcpStableVersion(String versionId) throws IOException {
        String metadata = VersionResolver.fetch(MCP_STABLE_METADATA_URL);
        Pattern pattern = Pattern.compile("<version>(\\d+)-" + Pattern.quote(versionId) + "</version>");
        Matcher matcher = pattern.matcher(metadata);
        int best = -1;
        while (matcher.find()) {
            best = Math.max(best, Integer.parseInt(matcher.group(1)));
        }
        return best < 0 ? null : best + "-" + versionId;
    }
}
