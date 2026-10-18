package com.jsd.aird.shared.office;

import java.util.Set;

public final class ArchiveFileNames {
    private ArchiveFileNames() { }

    public static String unique(String fileName, Set<String> usedNames) {
        var name = fileName == null ? "file" : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        name = name.replaceAll("^\\.+", "");
        if (name.isBlank()) name = "file";
        var dot = name.lastIndexOf('.');
        var extension = dot > 0 ? name.substring(dot) : "";
        var stem = dot > 0 ? name.substring(0, dot) : name;
        stem = stem.substring(0, Math.min(160, stem.length()));
        var candidate = stem + extension;
        for (var suffix = 2; !usedNames.add(candidate.toLowerCase(java.util.Locale.ROOT)); suffix++) candidate = stem + "_" + suffix + extension;
        return candidate;
    }
}
