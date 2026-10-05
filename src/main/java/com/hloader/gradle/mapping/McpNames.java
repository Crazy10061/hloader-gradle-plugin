package com.hloader.gradle.mapping;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Layers MCP's {@code mcp_stable}/{@code mcp_snapshot} CSV data (real human names, e.g.
 * {@code isSneaking}) on top of a {@link MappingSet} that currently only has Forge/MCP's raw SRG
 * intermediate names ({@code field_XXXXX_x}/{@code func_XXXXX_x}) as its "official" side.
 */
public final class McpNames {

    private McpNames() {
    }

    public static void applyFromZip(MappingSet mappings, byte[] zipBytes) throws IOException {
        Map<String, String> methodNames = new HashMap<>();
        Map<String, String> fieldNames = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String csv = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (entry.getName().equals("methods.csv")) {
                    readCsv(csv, methodNames);
                } else if (entry.getName().equals("fields.csv")) {
                    readCsv(csv, fieldNames);
                }
            }
        }

        mappings.methodsByObfKey.replaceAll((key, method) -> {
            String humanName = methodNames.get(method.officialName());
            return humanName == null ? method
                    : new MappingSet.MethodEntry(method.obfOwner(), method.obfName(), method.obfDescriptor(), humanName, method.officialDescriptor());
        });
        mappings.fieldsByObfKey.replaceAll((key, field) -> {
            String humanName = fieldNames.get(field.officialName());
            return humanName == null ? field : new MappingSet.FieldEntry(field.obfOwner(), field.obfName(), humanName);
        });
    }

    /** {@code searge,name,side,desc} - desc can itself contain commas, but we only need the first two columns. */
    private static void readCsv(String csv, Map<String, String> out) {
        boolean firstLine = true;
        for (String line : csv.split("\n")) {
            if (firstLine) {
                firstLine = false;
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 4);
            if (parts.length >= 2) {
                out.put(parts[0].trim(), parts[1].trim());
            }
        }
    }
}
