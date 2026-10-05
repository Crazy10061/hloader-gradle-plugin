package com.hloader.gradle.preprocess;

/** A parsed {@code //? if <condition>} expression, evaluated against the active Minecraft version. */
@FunctionalInterface
public interface VersionCondition {

    boolean test(String activeVersion);
}
