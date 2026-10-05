package com.hloader.gradle.mapping;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses OrnitheMC's {@code feather} mappings directly into a {@link MappingSet}. Unlike Legacy
 * Fabric's yarn (a Tiny v2 file with only an intermediary-&gt;named namespace, needing a separate
 * {@code calamus} intermediary jar composed in first - see {@link LegacyFabricMappings}), a
 * single feather jar's {@code mappings/mappings.tiny} already carries every namespace in one
 * Tiny v1 file, so no composition is needed.
 *
 * <p>The column layout is read from the header rather than assumed: gen2 feather is
 * {@code intermediary clientOfficial serverOfficial named}, with owners and descriptors in the
 * <i>first</i> namespace (intermediary) - not the obfuscated one. Each entry is translated to the
 * obfuscated names the client jar actually uses ({@code clientOfficial}, or {@code official} in
 * single-jar layouts); server-only entries fall back to {@code serverOfficial}.</p>
 *
 * <p>Covers roughly c0.0.12a_03-1.14.4; see <a href="https://github.com/OrnitheMC">github.com/OrnitheMC</a>.</p>
 */
public final class OrnitheMappings {

    private OrnitheMappings() {
    }

    public static MappingSet parse(String featherTiny) {
        MappingSet set = new MappingSet();
        String[] lines = featherTiny.split("\n");
        if (lines.length == 0) {
            return set;
        }

        // Header: "v1 <ns0> <ns1> ..." - name columns follow the same order in every entry.
        List<String> namespaces = Arrays.asList(lines[0].trim().split("\t")).subList(1, lines[0].trim().split("\t").length);
        int named = namespaces.indexOf("named");
        int[] obfColumns = obfuscatedColumns(namespaces);
        if (named < 0 || obfColumns.length == 0) {
            throw new IllegalArgumentException("Unrecognized feather mappings header: " + lines[0].trim());
        }

        // Classes first: owners and descriptors in every entry are written in the first
        // namespace, so both the obfuscated and the named class maps are keyed by it.
        Map<String, String> firstToObf = new HashMap<>();
        Map<String, String> firstToNamed = new HashMap<>();
        Set<String> clientSide = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", -1);
            if (!p[0].equals("CLASS")) {
                continue;
            }
            String first = p[1];
            String obf = pick(p, 1, obfColumns);
            String namedName = column(p, 1, named);
            if (obf == null) {
                continue;
            }
            firstToObf.put(first, obf);
            firstToNamed.put(first, namedName != null ? namedName : obf);
            if (column(p, 1, obfColumns[0]) != null) {
                clientSide.add(first);
            }
        }
        // Pre-1.3 client and server were obfuscated independently, so a server-only class can
        // reuse an obfuscated name a client class already has - the client (what actually runs
        // and what mods compile against) wins.
        for (boolean clientPass : new boolean[] {true, false}) {
            firstToObf.forEach((first, obf) -> {
                if (clientSide.contains(first) != clientPass || set.obfToOfficialClass.containsKey(obf)) {
                    return;
                }
                String namedName = firstToNamed.get(first);
                set.obfToOfficialClass.put(obf, namedName);
                set.officialToObfClass.putIfAbsent(namedName, obf);
            });
        }

        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", -1);
            boolean field = p[0].equals("FIELD");
            if (!field && !p[0].equals("METHOD")) {
                continue;
            }
            // FIELD/METHOD <owner> <descriptor> <name per namespace...>
            String obfOwner = firstToObf.get(p[1]);
            String obfName = pick(p, 3, obfColumns);
            String namedName = column(p, 3, named);
            if (obfOwner == null || obfName == null || namedName == null) {
                continue;
            }
            String obfDescriptor = DescriptorUtil.remapDescriptor(p[2], firstToObf);
            if (!clientSide.contains(p[1]) && set.obfToOfficialClass.get(obfOwner) != null
                    && !set.obfToOfficialClass.get(obfOwner).equals(firstToNamed.get(p[1]))) {
                continue; // member of a server-only class whose obfuscated name the client took
            }
            if (field) {
                set.fieldsByObfKey.put(MappingSet.fieldKey(obfOwner, obfName),
                        new MappingSet.FieldEntry(obfOwner, obfName, namedName));
            } else {
                String namedDescriptor = DescriptorUtil.remapDescriptor(p[2], firstToNamed);
                set.methodsByObfKey.put(MappingSet.methodKey(obfOwner, obfName, obfDescriptor),
                        new MappingSet.MethodEntry(obfOwner, obfName, obfDescriptor, namedName, namedDescriptor));
            }
        }

        return set;
    }

    /** Obfuscated-name columns, most preferred first: the client jar's names, then the server's. */
    private static int[] obfuscatedColumns(List<String> namespaces) {
        for (String single : List.of("official", "clientOfficial")) {
            int index = namespaces.indexOf(single);
            if (index >= 0) {
                int server = namespaces.indexOf("serverOfficial");
                return server >= 0 && !single.equals("official") ? new int[] {index, server} : new int[] {index};
            }
        }
        int server = namespaces.indexOf("serverOfficial");
        return server >= 0 ? new int[] {server} : new int[0];
    }

    /** The first non-empty name among {@code columns}, where names start at index {@code offset}. */
    private static String pick(String[] parts, int offset, int[] columns) {
        for (int column : columns) {
            String name = column(parts, offset, column);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private static String column(String[] parts, int offset, int column) {
        int index = offset + column;
        if (index >= parts.length) {
            return null;
        }
        String name = parts[index].trim();
        return name.isEmpty() ? null : name;
    }
}
