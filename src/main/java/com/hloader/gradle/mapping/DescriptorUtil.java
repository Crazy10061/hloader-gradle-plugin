package com.hloader.gradle.mapping;

import java.util.Map;

/** Converts Proguard mapping-file type names (Java source syntax) into JVM method/field descriptors. */
final class DescriptorUtil {

    private DescriptorUtil() {
    }

    static String sourceMethodDescriptor(String returnType, String paramsCsv) {
        StringBuilder sb = new StringBuilder("(");
        if (!paramsCsv.isBlank()) {
            for (String param : paramsCsv.split(",")) {
                sb.append(sourceTypeToDescriptor(param.trim()));
            }
        }
        sb.append(')').append(sourceTypeToDescriptor(returnType));
        return sb.toString();
    }

    static String sourceTypeToDescriptor(String type) {
        int arrayDims = 0;
        String base = type;
        while (base.endsWith("[]")) {
            arrayDims++;
            base = base.substring(0, base.length() - 2);
        }
        String descriptor = switch (base) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + base.replace('.', '/') + ";";
        };
        return "[".repeat(arrayDims) + descriptor;
    }

    /** Rewrites every embedded class reference in a descriptor using the given internal-name rename map. */
    static String remapDescriptor(String descriptor, Map<String, String> classRename) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                String internalName = descriptor.substring(i + 1, end);
                result.append('L').append(classRename.getOrDefault(internalName, internalName)).append(';');
                i = end + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
