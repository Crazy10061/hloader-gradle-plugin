package com.hloader.gradle.tasks;

import com.hloader.gradle.mapping.JarRemapper;
import com.hloader.gradle.mapping.MappingSet;
import com.hloader.gradle.mapping.SrgParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * Deobfuscates a game jar (obfuscated names -> Mojang's official names) so mods compile against
 * readable code. Reads the mapping back in from {@link GenerateMappings}' SRG *file* rather than
 * taking a live {@link MappingSet} object from that task instance - the latter would be empty
 * whenever Gradle decides generateMappings is already up-to-date and skips actually running it in
 * this build, since a freshly-constructed task object's in-memory field is never populated then.
 */
public abstract class RemapGameJar extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getInputJar();

    @InputFile
    public abstract RegularFileProperty getSrgFile();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void remap() throws IOException {
        String srgText = Files.readString(getSrgFile().get().getAsFile().toPath());
        MappingSet mappings = SrgParser.parse(srgText);
        if (mappings.obfToOfficialClass.isEmpty()) {
            Files.copy(getInputJar().get().getAsFile().toPath(), getOutputJar().get().getAsFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        JarRemapper.remap(getInputJar().get().getAsFile(), getOutputJar().get().getAsFile(), mappings);
        getLogger().lifecycle("hloader: deobfuscated game jar written to " + getOutputJar().get().getAsFile());
    }
}
