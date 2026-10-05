package com.hloader.gradle.preprocess;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * JSON-safe version gating for Sponge Mixin config files (any {@code *.mixins.json} resource -
 * hloader auto-discovers those by name at runtime, see {@code HloaderAgent.findMixinConfigs}).
 * {@code //} comments aren't valid JSON, so this can't reuse {@link CodePreprocessor}'s syntax;
 * instead, any entry in a config's {@code "mixins"}, {@code "client"}, or {@code "server"} array
 * may be either a plain string (kept unconditionally, exactly like today) or an object
 * {@code {"if": "<condition>", "class": "<mixin class name>"}} (see {@link VersionConditionParser}
 * for the condition grammar), which resolves down to just its {@code "class"} string when the
 * condition holds for the active version, and is dropped otherwise. The output is a plain,
 * standard mixin config - Sponge Mixin itself never sees the conditional form.
 */
public final class MixinConfigPreprocessor {

    private static final String[] MIXIN_LIST_KEYS = {"mixins", "client", "server"};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MixinConfigPreprocessor() {
    }

    public static String process(String json, String activeVersion) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (String key : MIXIN_LIST_KEYS) {
            JsonElement existing = root.get(key);
            if (existing != null && existing.isJsonArray()) {
                root.add(key, resolveList(existing.getAsJsonArray(), activeVersion));
            }
        }
        return GSON.toJson(root);
    }

    private static JsonArray resolveList(JsonArray input, String activeVersion) {
        JsonArray output = new JsonArray();
        for (JsonElement element : input) {
            if (element.isJsonPrimitive()) {
                output.add(element);
                continue;
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Mixin list entry must be a string or a {\"if\": ..., \"class\": ...} object, got: " + element);
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("if") || !entry.has("class")) {
                throw new IllegalArgumentException(
                        "Conditional mixin list entry needs both \"if\" and \"class\": " + entry);
            }
            String condition = entry.get("if").getAsString();
            boolean active;
            try {
                active = VersionConditionParser.parse(condition).test(activeVersion);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "Invalid version condition in mixin list (\"" + condition + "\"): " + e.getMessage(), e);
            }
            if (active) {
                output.add(new JsonPrimitive(entry.get("class").getAsString()));
            }
        }
        return output;
    }
}
