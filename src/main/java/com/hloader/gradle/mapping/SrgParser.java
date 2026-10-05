package com.hloader.gradle.mapping;

/**
 * Parses SRG-format mappings directly (Forge/MCP's {@code joined.srg}) into a {@link MappingSet}.
 * Unlike Mojang's Proguard mappings, SRG already lists both sides' JVM descriptors verbatim, so no
 * source-type-to-descriptor conversion is needed.
 */
public final class SrgParser {

    private SrgParser() {
    }

    public static MappingSet parse(String text) {
        MappingSet set = new MappingSet();

        for (String line : text.split("\n")) {
            if (line.startsWith("CL: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                set.obfToOfficialClass.put(parts[0], parts[1]);
                set.officialToObfClass.put(parts[1], parts[0]);
            }
        }

        for (String line : text.split("\n")) {
            if (line.startsWith("MD: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                String obfOwnerAndName = parts[0];
                String obfDescriptor = parts[1];
                String namedOwnerAndName = parts[2];
                String namedDescriptor = parts[3];
                int obfSlash = obfOwnerAndName.lastIndexOf('/');
                int namedSlash = namedOwnerAndName.lastIndexOf('/');
                String obfOwner = obfOwnerAndName.substring(0, obfSlash);
                String obfName = obfOwnerAndName.substring(obfSlash + 1);
                String namedOwner = namedOwnerAndName.substring(0, namedSlash);
                String namedName = namedOwnerAndName.substring(namedSlash + 1);
                backfillClassMapping(set, obfOwner, namedOwner);
                set.methodsByObfKey.put(MappingSet.methodKey(obfOwner, obfName, obfDescriptor),
                        new MappingSet.MethodEntry(obfOwner, obfName, obfDescriptor, namedName, namedDescriptor));
            } else if (line.startsWith("FD: ")) {
                String[] parts = line.substring(4).trim().split(" ");
                String obfOwnerAndName = parts[0];
                String namedOwnerAndName = parts[1];
                int obfSlash = obfOwnerAndName.lastIndexOf('/');
                int namedSlash = namedOwnerAndName.lastIndexOf('/');
                String obfOwner = obfOwnerAndName.substring(0, obfSlash);
                String obfName = obfOwnerAndName.substring(obfSlash + 1);
                String namedOwner = namedOwnerAndName.substring(0, namedSlash);
                String namedName = namedOwnerAndName.substring(namedSlash + 1);
                backfillClassMapping(set, obfOwner, namedOwner);
                set.fieldsByObfKey.put(MappingSet.fieldKey(obfOwner, obfName), new MappingSet.FieldEntry(obfOwner, obfName, namedName));
            }
        }

        return set;
    }

    /**
     * Forge/MCP's joined.srg doesn't always have a standalone {@code CL:} line for every class its
     * {@code MD:}/{@code FD:} lines reference (e.g. it can rename a class's members while leaving
     * the class itself without an explicit rename entry) - backfilling from member lines too keeps
     * {@link MappingSet#obfToOfficialClass} consistent with what those lines actually say, so
     * {@link SrgWriter} doesn't end up describing a member owned by a class it never mentions.
     */
    private static void backfillClassMapping(MappingSet set, String obfOwner, String namedOwner) {
        if (!obfOwner.equals(namedOwner)) {
            set.obfToOfficialClass.putIfAbsent(obfOwner, namedOwner);
            set.officialToObfClass.putIfAbsent(namedOwner, obfOwner);
        }
    }
}
