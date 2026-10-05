package com.hloader.gradle.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Proguard-format mapping file Mojang publishes for each version
 * ({@code downloads.client_mappings}/{@code server_mappings}). These map
 * OFFICIAL (readable) names to the OBFUSCATED names actually baked into the
 * distributed jar, e.g.:
 *
 * <pre>
 * net.minecraft.world.entity.Entity -> ave:
 *     int qb -> a
 *     45:52:void tick() -> h
 * </pre>
 */
public final class ProguardMappings {

    private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern FIELD_LINE = Pattern.compile("^ {4}(\\S+) (\\S+) -> (\\S+)$");
    private static final Pattern METHOD_LINE = Pattern.compile("^ {4}(?:\\d+:\\d+:)?(\\S+) (\\S+)\\(([^)]*)\\) -> (\\S+)$");

    record MethodKey(String officialOwner, String officialName, String officialDescriptor) {
    }

    record FieldKey(String officialOwner, String officialName) {
    }

    private final Map<String, String> officialToObfClass = new HashMap<>();
    private final Map<String, String> obfToOfficialClass = new HashMap<>();
    private final Map<MethodKey, String> officialToObfMethod = new HashMap<>();
    private final Map<FieldKey, String> officialToObfField = new HashMap<>();

    Map<String, String> officialToObfClass() {
        return officialToObfClass;
    }

    Map<String, String> obfToOfficialClass() {
        return obfToOfficialClass;
    }

    Map<MethodKey, String> officialToObfMethod() {
        return officialToObfMethod;
    }

    Map<FieldKey, String> officialToObfField() {
        return officialToObfField;
    }

    public static ProguardMappings empty() {
        return new ProguardMappings();
    }

    public static ProguardMappings parse(String text) {
        ProguardMappings mappings = new ProguardMappings();
        String currentOfficialClass = null;

        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher classMatcher = CLASS_LINE.matcher(line);
            if (classMatcher.matches()) {
                currentOfficialClass = classMatcher.group(1);
                String obf = classMatcher.group(2);
                mappings.officialToObfClass.put(currentOfficialClass, obf);
                mappings.obfToOfficialClass.put(obf, currentOfficialClass);
                continue;
            }
            if (currentOfficialClass == null) {
                continue;
            }

            Matcher methodMatcher = METHOD_LINE.matcher(line);
            if (methodMatcher.matches()) {
                String returnType = methodMatcher.group(1);
                String name = methodMatcher.group(2);
                String params = methodMatcher.group(3);
                String obfName = methodMatcher.group(4);
                String descriptor = DescriptorUtil.sourceMethodDescriptor(returnType, params);
                mappings.officialToObfMethod.put(new MethodKey(currentOfficialClass, name, descriptor), obfName);
                continue;
            }

            Matcher fieldMatcher = FIELD_LINE.matcher(line);
            if (fieldMatcher.matches()) {
                String name = fieldMatcher.group(2);
                String obfName = fieldMatcher.group(3);
                mappings.officialToObfField.put(new FieldKey(currentOfficialClass, name), obfName);
            }
        }

        return mappings;
    }

    List<String> officialClassNames() {
        return new ArrayList<>(officialToObfClass.keySet());
    }
}
