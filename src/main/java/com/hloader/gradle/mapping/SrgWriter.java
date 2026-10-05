package com.hloader.gradle.mapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Writes an SRG-format mapping file from a {@link MappingSet} - the format Mixin's annotation
 * processor expects (via {@code -AreobfSrgFile}) to generate refmaps that translate a mixin's
 * readable ("named"/official) references back to the obfuscated names the real game jar uses.
 */
public final class SrgWriter {

    private SrgWriter() {
    }

    public static void write(MappingSet mappings, File output) throws IOException {
        write(mappings, output, false);
    }

    /**
     * Mixin's annotation processor's {@code MappingProviderSrg} parses each SRG line's columns
     * verbatim into a direct (non-inverse) lookup map, then queries it with the mixin's own
     * (named/compiled-side) class or member as the key to get the obfuscated one back
     * ({@code ObfuscationEnvironment.getObfMethod}/{@code getObfClass} call
     * {@code MappingProvider.getMethodMapping}/{@code getClassMapping}, both a plain
     * {@code map.get(argument)}). That means the AP needs the OPPOSITE column order
     * ({@code <named> <obf>}) from the standard {@code joined.srg} convention (and from what
     * {@link SrgParser}/{@link RemapGameJar} expect for their own obf-first round-trip), so this
     * file is written separately from - and with reversed columns compared to - the one written
     * via {@link #write(MappingSet, File)}.
     */
    public static void writeReversed(MappingSet mappings, File output) throws IOException {
        write(mappings, output, true);
    }

    private static void write(MappingSet mappings, File output, boolean reversed) throws IOException {
        StringBuilder sb = new StringBuilder();

        mappings.obfToOfficialClass.forEach((obf, official) -> {
            String first = reversed ? official : obf;
            String second = reversed ? obf : official;
            sb.append("CL: ").append(first).append(' ').append(second).append('\n');
        });

        mappings.fieldsByObfKey.values().forEach(field -> {
            String namedOwner = mappings.obfToOfficialClass.getOrDefault(field.obfOwner(), field.obfOwner());
            String obfEntry = field.obfOwner() + '/' + field.obfName();
            String namedEntry = namedOwner + '/' + field.officialName();
            String first = reversed ? namedEntry : obfEntry;
            String second = reversed ? obfEntry : namedEntry;
            sb.append("FD: ").append(first).append(' ').append(second).append('\n');
        });

        mappings.methodsByObfKey.values().forEach(method -> {
            String namedOwner = mappings.obfToOfficialClass.getOrDefault(method.obfOwner(), method.obfOwner());
            String obfEntry = method.obfOwner() + '/' + method.obfName() + ' ' + method.obfDescriptor();
            String namedEntry = namedOwner + '/' + method.officialName() + ' ' + method.officialDescriptor();
            String first = reversed ? namedEntry : obfEntry;
            String second = reversed ? obfEntry : namedEntry;
            sb.append("MD: ").append(first).append(' ').append(second).append('\n');
        });

        Files.createDirectories(output.toPath().getParent());
        Files.writeString(output.toPath(), sb.toString());
    }
}
