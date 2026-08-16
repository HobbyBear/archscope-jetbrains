package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ReportLanguageValidator {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Set<String> TECHNICAL_FIELDS = Set.of(
            "file", "path", "symbol", "label", "commit", "subject", "author", "repository_root",
            "selected_commits", "commit_ids", "changed_in_commits", "evidence_paths", "changed_paths",
            "unmapped_changed_paths", "base_commit", "target_commit", "fingerprint"
    );

    private ReportLanguageValidator() {
    }

    static void validate(JsonObject report, AnalysisRequest.OutputLanguage language) throws ModelClientException {
        if (!language.isEnglish()) return;
        String path = firstHanPath(report, "$", "");
        if (path != null) {
            throw new ModelClientException(
                    "English output contains Chinese text at " + path
                            + ". Translate every human-readable value to English and return the complete JSON again."
            );
        }
    }

    static boolean containsHan(String value) {
        return value != null && HAN.matcher(value).find();
    }

    private static String firstHanPath(JsonElement element, String path, String field) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            return !TECHNICAL_FIELDS.contains(field)
                    && element.getAsJsonPrimitive().isString() && containsHan(element.getAsString()) ? path : null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                String found = firstHanPath(array.get(index), path + "[" + index + "]", field);
                if (found != null) return found;
            }
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String found = firstHanPath(entry.getValue(), path + "." + entry.getKey(), entry.getKey());
            if (found != null) return found;
        }
        return null;
    }
}
