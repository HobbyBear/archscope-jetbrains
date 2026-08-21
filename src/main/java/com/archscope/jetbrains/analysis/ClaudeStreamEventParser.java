package com.archscope.jetbrains.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ClaudeStreamEventParser {
    private static final int TOOL_RESULT_LIMIT = 1600;
    private final Set<String> toolCalls = new HashSet<>();
    private final Set<String> toolResults = new HashSet<>();
    private String streamedText = "";
    private String streamedThinking = "";
    private String lastAssistantText = "";
    private String lastAssistantThinking = "";

    List<ModelStreamEvent> accept(JsonObject envelope) {
        List<ModelStreamEvent> events = new ArrayList<>();
        String type = string(envelope, "type");
        if ("stream_event".equals(type)) {
            parsePartial(object(envelope, "event"), events);
        } else if ("assistant".equals(type) || "user".equals(type)) {
            parseMessage(object(envelope, "message"), events);
        } else if ("system".equals(type) && "init".equals(string(envelope, "subtype"))) {
            events.add(ModelStreamEvent.status("Claude CLI session initialized"));
        } else if ("result".equals(type)) {
            if (booleanValue(envelope, "is_error")) {
                events.add(ModelStreamEvent.error(nonBlank(string(envelope, "result"), string(envelope, "subtype"))));
            } else {
                events.add(ModelStreamEvent.status("Claude CLI completed"));
            }
        }
        return events;
    }

    private void parsePartial(JsonObject event, List<ModelStreamEvent> output) {
        if (event == null) return;
        String type = string(event, "type");
        if ("content_block_start".equals(type)) {
            JsonObject block = object(event, "content_block");
            if (block == null) return;
            if ("text".equals(string(block, "type"))) streamedText = "";
            if ("thinking".equals(string(block, "type"))) streamedThinking = "";
            return;
        }
        if (!"content_block_delta".equals(type)) return;
        JsonObject delta = object(event, "delta");
        if (delta == null) return;
        if ("text_delta".equals(string(delta, "type"))) {
            String text = string(delta, "text");
            streamedText += text;
            if (!text.isEmpty()) output.add(ModelStreamEvent.response(text));
        } else if ("thinking_delta".equals(string(delta, "type"))) {
            String thinking = string(delta, "thinking");
            streamedThinking += thinking;
            if (!thinking.isEmpty()) output.add(ModelStreamEvent.reasoning(thinking));
        }
    }

    private void parseMessage(JsonObject message, List<ModelStreamEvent> output) {
        JsonArray content = message == null ? null : array(message, "content");
        if (content == null) return;
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            switch (string(block, "type")) {
                case "text" -> emitSnapshotText(string(block, "text"), output);
                case "thinking" -> emitSnapshotThinking(string(block, "thinking"), output);
                case "tool_use", "server_tool_use" -> emitToolCall(block, output);
                case "tool_result" -> emitToolResult(block, output);
                default -> {
                }
            }
        }
    }

    private void emitSnapshotText(String text, List<ModelStreamEvent> output) {
        if (text.isEmpty()) return;
        if (!streamedText.isEmpty() && text.startsWith(streamedText)) {
            String remainder = text.substring(streamedText.length());
            if (!remainder.isEmpty()) output.add(ModelStreamEvent.response(remainder));
            lastAssistantText = text;
            streamedText = "";
            return;
        }
        String delta = progressiveDelta(lastAssistantText, text);
        lastAssistantText = text;
        if (!delta.isEmpty()) output.add(ModelStreamEvent.response(delta));
    }

    private void emitSnapshotThinking(String thinking, List<ModelStreamEvent> output) {
        if (thinking.isEmpty()) return;
        if (!streamedThinking.isEmpty() && thinking.startsWith(streamedThinking)) {
            String remainder = thinking.substring(streamedThinking.length());
            if (!remainder.isEmpty()) output.add(ModelStreamEvent.reasoning(remainder));
            lastAssistantThinking = thinking;
            streamedThinking = "";
            return;
        }
        String delta = progressiveDelta(lastAssistantThinking, thinking);
        lastAssistantThinking = thinking;
        if (!delta.isEmpty()) output.add(ModelStreamEvent.reasoning(delta));
    }

    private void emitToolCall(JsonObject block, List<ModelStreamEvent> output) {
        String id = string(block, "id");
        String name = nonBlank(string(block, "name"), "tool");
        String fingerprint = id.isBlank() ? name + "\n" + value(block, "input") : id;
        if (!toolCalls.add(fingerprint)) return;
        output.add(ModelStreamEvent.toolCall(name, pretty(value(block, "input"))));
    }

    private void emitToolResult(JsonObject block, List<ModelStreamEvent> output) {
        String id = string(block, "tool_use_id");
        String content = pretty(value(block, "content"));
        String fingerprint = id.isBlank() ? content : id;
        if (!toolResults.add(fingerprint)) return;
        output.add(ModelStreamEvent.toolResult(id, abbreviate(content, TOOL_RESULT_LIMIT)));
    }

    private static String progressiveDelta(String previous, String current) {
        if (!previous.isEmpty() && current.startsWith(previous)) return current.substring(previous.length());
        if (!current.isEmpty() && previous.startsWith(current)) return "";
        return current;
    }

    private static String pretty(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : value.toString();
    }

    private static JsonElement value(JsonObject owner, String name) {
        return owner != null && owner.has(name) ? owner.get(name) : null;
    }

    private static JsonObject object(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonObject()
                ? owner.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonArray()
                ? owner.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive()
                ? owner.get(name).getAsString() : "";
    }

    private static boolean booleanValue(JsonObject owner, String name) {
        try {
            return owner != null && owner.has(name) && owner.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
