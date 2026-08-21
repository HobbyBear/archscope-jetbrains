package com.archscope.jetbrains.analysis;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClaudeStreamEventParserTest {
    @Test
    void streamsReasoningAndResponseWithoutRepeatingAssistantSnapshots() {
        ClaudeStreamEventParser parser = new ClaudeStreamEventParser();
        List<ModelStreamEvent> events = new ArrayList<>();
        events.addAll(parse(parser, """
                {"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"thinking"}}}
                """));
        events.addAll(parse(parser, """
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"检查调用"}}}
                """));
        events.addAll(parse(parser, """
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"检查调用"}]}}
                """));
        events.addAll(parse(parser, """
                {"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"text"}}}
                """));
        events.addAll(parse(parser, """
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"{\\\"schema\\\":"}}}
                """));
        events.addAll(parse(parser, """
                {"type":"assistant","message":{"content":[{"type":"text","text":"{\\\"schema\\\":"}]}}
                """));

        assertEquals(List.of(ModelStreamEvent.Kind.REASONING, ModelStreamEvent.Kind.RESPONSE),
                events.stream().map(ModelStreamEvent::kind).toList());
        assertEquals("检查调用", events.get(0).content());
        assertEquals("{\"schema\":", events.get(1).content());
    }

    @Test
    void exposesToolCallsAndResultsOnce() {
        ClaudeStreamEventParser parser = new ClaudeStreamEventParser();
        List<ModelStreamEvent> events = parse(parser, """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool-1","name":"Grep","input":{"pattern":"CreateTask"}}]}}
                """);
        events.addAll(parse(parser, """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool-1","name":"Grep","input":{"pattern":"CreateTask"}}]}}
                """));
        events.addAll(parse(parser, """
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"service/video_sd.go:141"}]}}
                """));

        assertEquals(List.of(ModelStreamEvent.Kind.TOOL_CALL, ModelStreamEvent.Kind.TOOL_RESULT),
                events.stream().map(ModelStreamEvent::kind).toList());
        assertEquals("Grep", events.get(0).title());
        assertEquals("service/video_sd.go:141", events.get(1).content());
    }

    private static List<ModelStreamEvent> parse(ClaudeStreamEventParser parser, String json) {
        return new ArrayList<>(parser.accept(JsonParser.parseString(json).getAsJsonObject()));
    }
}
