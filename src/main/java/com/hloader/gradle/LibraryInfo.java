package com.hloader.gradle;

/** One entry from a version's "libraries" list, already filtered to the current OS. */
public record LibraryInfo(String url, String path, boolean isNative) {
}
