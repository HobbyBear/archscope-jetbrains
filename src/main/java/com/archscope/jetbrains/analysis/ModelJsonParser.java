package com.archscope.jetbrains.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.LinkedHashSet;

final class ModelJsonParser {
    private ModelJsonParser() {
    }

    static JsonObject parseObject(String raw) {
        String normalized = raw == null ? "" : raw.strip();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        String unfenced = stripFence(normalized);
        candidates.add(unfenced);
        String object = firstCompleteObject(unfenced);
        if (object != null) candidates.add(object);

        RuntimeException last = new IllegalArgumentException("empty model response");
        for (String candidate : candidates) {
            if (candidate.isBlank()) continue;
            try {
                JsonElement parsed = JsonParser.parseString(candidate);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                last = exception;
            }
            try {
                JsonReader reader = new JsonReader(new StringReader(candidate));
                reader.setStrictness(Strictness.LENIENT);
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw last;
    }

    private static String stripFence(String value) {
        if (!value.startsWith("```")) return value;
        int firstLine = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        return firstLine >= 0 && lastFence > firstLine
                ? value.substring(firstLine + 1, lastFence).strip()
                : value;
    }

    private static String firstCompleteObject(String value) {
        int start = value.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return value.substring(start, index + 1);
        }
        return null;
    }
}
