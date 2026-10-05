package com.hloader.gradle;

import java.util.List;

/**
 * Parsed version JSON, trimmed to what launching either side needs.
 *
 * @param dedicatedServer false when this is a {@code side="server"} fetch for a version with no
 *     real server download in Mojang's manifest (pre-dedicated-server-distribution versions, e.g.
 *     1.0) - {@code downloadUrl} then points at the client jar instead, a fine substitute for
 *     compiling against but not something that's actually runnable as a server. Always true for
 *     {@code side="client"} fetches.
 */
public record MinecraftVersionInfo(
        String versionId,
        String mainClass,
        String downloadUrl,
        String assetIndexId,
        String assetIndexUrl,
        String mappingsUrl,
        List<LibraryInfo> libraries,
        boolean dedicatedServer
) {
}
