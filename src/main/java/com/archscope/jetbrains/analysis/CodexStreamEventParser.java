package com.archscope.jetbrains.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CodexStreamEventParser {
    private static final int RESULT_LIMIT = 1600;
    private final Set<String> startedTools = new HashSet<>();
    private final Set<String> completedItems = new HashSet<>();

    List<ModelStreamEvent> accept(JsonObject event) {
        List<ModelStreamEvent> output = new ArrayList<>();
        String eventType = string(event, "type");
        JsonObject item = object(event, "item");
        if (item == null) {
            if ("turn.started".equals(eventType)) output.add(ModelStreamEvent.status("Codex turn started"));
            if ("turn.completed".equals(eventType)) output.add(ModelStreamEvent.status("Codex turn completed"));
            if ("turn.failed".equals(eventType) || "error".equals(eventType)) {
                output.add(ModelStreamEvent.error(nonBlank(string(event, "message"), event.toString())));
            }
            return output;
        }

        String itemType = string(item, "type");
        String id = nonBlank(string(item, "id"), itemType + "\n" + item);
        if ("item.started".equals(eventType) && isTool(itemType) && startedTools.add(id)) {
            output.add(ModelStreamEvent.toolCall(toolName(item), toolInput(item)));
            return output;
        }
        if (!"item.completed".equals(eventType) || !completedItems.add(id)) return output;

        switch (itemType) {
            case "agent_message" -> output.add(ModelStreamEvent.response(string(item, "text")));
            case "reasoning" -> output.add(ModelStreamEvent.reasoning(nonBlank(string(item, "text"), summaryText(item))));
            case "command_execution", "mcp_tool_call" -> {
                if (startedTools.add(id)) output.add(ModelStreamEvent.toolCall(toolName(item), toolInput(item)));
                output.add(ModelStreamEvent.toolResult(id, abbreviate(toolOutput(item), RESULT_LIMIT)));
            }
            default -> {
            }
        }
        return output;
    }

    private static boolean isTool(String itemType) {
        return "command_execution".equals(itemType) || "mcp_tool_call".equals(itemType);
    }

    private static String toolName(JsonObject item) {
        if ("command_execution".equals(string(item, "type"))) return "command_execution";
        return nonBlank(string(item, "tool"), "mcp_tool_call");
    }

    private static String toolInput(JsonObject item) {
        if ("command_execution".equals(string(item, "type"))) return string(item, "command");
        JsonElement arguments = value(item, "arguments");
        return arguments == null ? "" : arguments.toString();
    }

    private static String toolOutput(JsonObject item) {
        String output = string(item, "aggregated_output");
        if (output.isBlank()) output = string(item, "output");
        if (output.isBlank()) output = string(item, "result");
        if (output.isBlank()) output = summaryText(item);
        return output;
    }

    private static String summaryText(JsonObject item) {
        JsonElement summary = value(item, "summary");
        return summary == null ? "" : summary.isJsonPrimitive() ? summary.getAsString() : summary.toString();
    }

    private static JsonElement value(JsonObject owner, String name) {
        return owner != null && owner.has(name) ? owner.get(name) : null;
    }

    private static JsonObject object(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonObject()
                ? owner.getAsJsonObject(name) : null;
    }

    private static String string(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive()
                ? owner.get(name).getAsString() : "";
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
