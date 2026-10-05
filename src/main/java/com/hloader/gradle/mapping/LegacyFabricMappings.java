package com.hloader.gradle.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes Legacy Fabric's two-layer Tiny mappings - {@code intermediary} (obfuscated -&gt;
 * Legacy Fabric's own stable intermediate names, Tiny v1) then {@code yarn} (intermediary -&gt;
 * human-readable names, Tiny v2) - directly into a {@link MappingSet}, the same way
 * {@link SrgParser} does for Forge/MCP's SRG format (both already carry obfuscated-side
 * descriptors verbatim, unlike Mojang's Proguard mappings, so neither needs the
 * {@link ProguardMappings} detour). Covers roughly 1.3.2-1.13.2; see
 * <a href="https://github.com/Legacy-Fabric">github.com/Legacy-Fabric</a>.
 */
public final class LegacyFabricMappings {

    private LegacyFabricMappings() {
    }

    /** owner/descriptor are in the mapping's "from" namespace. */
    private record MemberRow(String owner, String descriptor, String fromName, String toName) {
    }

    private record TinyMap(Map<String, String> classes, List<MemberRow> methods, List<MemberRow> fields) {
    }

    public static MappingSet compose(String intermediaryTiny, String yarnTiny) {
        TinyMap obfToIntermediary = parseV1(intermediaryTiny);
        TinyMap intermediaryToNamed = parseV2(yarnTiny);

        MappingSet set = new MappingSet();

        // Slash-form obf -> named class map, so member descriptors can be remapped straight from
        // obf to named in one step.
        Map<String, String> obfToNamedClass = new HashMap<>();
        for (Map.Entry<String, String> e : obfToIntermediary.classes().entrySet()) {
            String named = intermediaryToNamed.classes().getOrDefault(e.getValue(), e.getValue());
            obfToNamedClass.put(e.getKey(), named);
            set.obfToOfficialClass.put(e.getKey(), named);
            set.officialToObfClass.putIfAbsent(named, e.getKey());
        }

        Map<String, MemberRow> namedMethodsByIntermediaryKey = indexByOwnerFromNameAndDescriptor(intermediaryToNamed.methods());
        Map<String, MemberRow> namedFieldsByIntermediaryKey = indexByOwnerFromName(intermediaryToNamed.fields());

        for (MemberRow m : obfToIntermediary.methods()) {
            String intermediaryOwner = obfToIntermediary.classes().getOrDefault(m.owner(), m.owner());
            String intermediaryDescriptor = DescriptorUtil.remapDescriptor(m.descriptor(), obfToIntermediary.classes());
            String intermediaryName = m.toName();
            MemberRow named = namedMethodsByIntermediaryKey.get(intermediaryOwner + "." + intermediaryName + intermediaryDescriptor);
            String namedName = named != null ? named.toName() : intermediaryName;
            String namedDescriptor = DescriptorUtil.remapDescriptor(m.descriptor(), obfToNamedClass);
            set.methodsByObfKey.put(MappingSet.methodKey(m.owner(), m.fromName(), m.descriptor()),
                    new MappingSet.MethodEntry(m.owner(), m.fromName(), m.descriptor(), namedName, namedDescriptor));
        }

        for (MemberRow f : obfToIntermediary.fields()) {
            String intermediaryOwner = obfToIntermediary.classes().getOrDefault(f.owner(), f.owner());
            String intermediaryName = f.toName();
            MemberRow named = namedFieldsByIntermediaryKey.get(intermediaryOwner + "." + intermediaryName);
            String namedName = named != null ? named.toName() : intermediaryName;
            set.fieldsByObfKey.put(MappingSet.fieldKey(f.owner(), f.fromName()),
                    new MappingSet.FieldEntry(f.owner(), f.fromName(), namedName));
        }

        return set;
    }

    private static Map<String, MemberRow> indexByOwnerFromNameAndDescriptor(List<MemberRow> rows) {
        Map<String, MemberRow> result = new HashMap<>();
        for (MemberRow row : rows) {
            result.put(row.owner() + "." + row.fromName() + row.descriptor(), row);
        }
        return result;
    }

    private static Map<String, MemberRow> indexByOwnerFromName(List<MemberRow> rows) {
        Map<String, MemberRow> result = new HashMap<>();
        for (MemberRow row : rows) {
            result.put(row.owner() + "." + row.fromName(), row);
        }
        return result;
    }

    /** Tiny v1 (the intermediary jar): {@code CLASS/FIELD/METHOD <owner> [<descriptor>] <from> <to>}. */
    private static TinyMap parseV1(String text) {
        Map<String, String> classes = new HashMap<>();
        List<MemberRow> methods = new ArrayList<>();
        List<MemberRow> fields = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\t");
            switch (p[0]) {
                case "CLASS" -> classes.put(p[1], p[2]);
                case "FIELD" -> fields.add(new MemberRow(p[1], p[2], p[3], p[4]));
                case "METHOD" -> methods.add(new MemberRow(p[1], p[2], p[3], p[4]));
                default -> { }
            }
        }
        return new TinyMap(classes, methods, fields);
    }

    /** Tiny v2 (the yarn jar): {@code c <from> <to>}, then tab-indented {@code f/m <descriptor> <from> <to>}. */
    private static TinyMap parseV2(String text) {
        Map<String, String> classes = new HashMap<>();
        List<MemberRow> methods = new ArrayList<>();
        List<MemberRow> fields = new ArrayList<>();
        String currentClass = null;
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("tiny\t")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == '\t') {
                indent++;
            }
            String[] p = line.substring(indent).split("\t");
            if (indent == 0 && p[0].equals("c")) {
                currentClass = p[1];
                classes.put(p[1], p[2]);
            } else if (indent == 1 && currentClass != null && p.length >= 4) {
                if (p[0].equals("f")) {
                    fields.add(new MemberRow(currentClass, p[1], p[2], p[3]));
                } else if (p[0].equals("m")) {
                    methods.add(new MemberRow(currentClass, p[1], p[2], p[3]));
                }
            }
        }
        return new TinyMap(classes, methods, fields);
    }
}
