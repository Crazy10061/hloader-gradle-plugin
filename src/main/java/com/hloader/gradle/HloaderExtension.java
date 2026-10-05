package com.hloader.gradle;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/** {@code hloader { minecraftVersion.set("26.3") } } — "latest" resolves the current release. */
public interface HloaderExtension {

    Property<String> getMinecraftVersion();

    /**
     * Versions old enough to boot through {@code net.minecraft.launchwrapper.Launch} (roughly
     * pre-1.6) have a long list of known bugs on modern JDKs and modern Java/LWJGL - a Java 9+
     * classloader assumption that no longer holds, AWT-only mouse/window handling, missing
     * {@code main()} entrypoints for applet-only Classic builds, and more. Rather than
     * reimplementing every one of those fixes ourselves, when this is {@code true} (the
     * default) hloader swaps the vanilla {@code net.minecraft:launchwrapper} dependency for
     * MCPHackers' actively-maintained fork (<a href="https://github.com/MCPHackers/LaunchWrapper">
     * github.com/MCPHackers/LaunchWrapper</a>), which is a drop-in replacement (same
     * {@code net.minecraft.launchwrapper.Launch} class) that fixes them. Versions that don't use
     * LaunchWrapper at all are completely unaffected either way. Set to {@code false} to keep
     * the untouched vanilla behavior instead.
     */
    Property<Boolean> getPatchLegacyLaunchWrapper();

    /**
     * Versions old enough to have no official Mojang mappings fall back to Forge/MCP's
     * {@code joined.srg}, which only gives SRG intermediate names ({@code field_147145_h},
     * {@code func_70071_a_}) - real human names need MCP's separately-versioned
     * {@code mcp_stable} CSV data layered on top. Set this to the numeric revision (e.g.
     * {@code "12"} for Minecraft 1.7.10's {@code mcp_stable:12-1.7.10}) to pick a specific
     * mapping revision; leave unset to auto-resolve the newest {@code mcp_stable} revision
     * published for the current {@code minecraftVersion}. Versions with real official Mojang
     * mappings ignore this entirely (they're already human-readable).
     */
    Property<String> getMcpMappingVersion();

    /**
     * Which mapping source {@link com.hloader.gradle.tasks.GenerateMappings} should use:
     * <ul>
     * <li>{@code "auto"} (the default) - Mojang's official mappings when available, else Forge/MCP's
     * {@code joined.srg}+{@code mcp_stable}, else OrnitheMC's {@code feather}, else Legacy Fabric's
     * {@code intermediary}+{@code yarn} (whichever of these actually has real human names first), else
     * raw obfuscated names.</li>
     * <li>{@code "mojang"} - require Mojang's official mappings; fails if this version doesn't have any (pre-1.14.4).</li>
     * <li>{@code "mcp"} - require Forge/MCP's {@code joined.srg} (+ {@code mcp_stable} if available); fails below 1.6.4.</li>
     * <li>{@code "ornithe"} - require OrnitheMC's {@code feather}; fails outside roughly c0.0.12a_03-1.14.4.</li>
     * <li>{@code "legacy-fabric"} - require Legacy Fabric's {@code intermediary}+{@code yarn}; fails outside roughly 1.3.2-1.13.2.</li>
     * <li>{@code "none"} - skip deobfuscation entirely, keep raw obfuscated names.</li>
     * </ul>
     * Set this when you specifically want one source over another - e.g. Legacy Fabric's Yarn
     * names instead of MCP's for a version both cover, like 1.12.2.
     */
    Property<String> getMappingProvider();

    /**
     * Per-version overrides for {@link #getMappingProvider()}, keyed by exact
     * {@code minecraftVersion} id (e.g. {@code mappingProviderOverrides.put("1.0", "legacy-fabric")}).
     * Useful when a project spans several versions (or you flip {@code minecraftVersion} around
     * while testing) and different ones need different sources - an entry here wins over the
     * global {@link #getMappingProvider()} for that one version only; every other version keeps
     * using the global setting.
     */
    MapProperty<String, String> getMappingProviderOverrides();

    /**
     * When {@code true}, {@code src/main/java} and {@code src/main/resources} are run through
     * hloader's Stonecutter-compatible preprocessor before compilation, letting one shared source
     * tree target several {@link #getMinecraftVersion()}s:
     * <ul>
     * <li>every {@code .java} file and every {@code .cfg} access-transformer file gets
     * {@code //? if <condition> { ... //? }} blocks and {@code //$$}-prefixed lines toggled on or
     * off - see {@link com.hloader.gradle.preprocess.CodePreprocessor} for that syntax;</li>
     * <li>every {@code *.mixins.json} mixin config gets its {@code "mixins"}/{@code "client"}/
     * {@code "server"} lists resolved instead, via a JSON-native conditional entry shape ({@code //}
     * comments aren't valid JSON) - see {@link com.hloader.gradle.preprocess.MixinConfigPreprocessor}.</li>
     * </ul>
     * Every other resource (e.g. {@code hloader.mod.json}, a non-mixin {@code .json} file) is copied
     * through unchanged. Defaults to {@code false} - compilation reads {@code src/main/java} and
     * {@code src/main/resources} directly, as before.
     */
    Property<Boolean> getPreprocessSources();
}
