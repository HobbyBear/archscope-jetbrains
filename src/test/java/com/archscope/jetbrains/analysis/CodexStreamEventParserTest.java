package com.archscope.jetbrains.analysis;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CodexStreamEventParserTest {
    @Test
    void exposesCommandLifecycleAndFinalResponse() {
        CodexStreamEventParser parser = new CodexStreamEventParser();
        List<ModelStreamEvent> events = new ArrayList<>();
        events.addAll(parse(parser, """
                {"type":"item.started","item":{"id":"cmd-1","type":"command_execution","command":"rg CreateTask"}}
                """));
        events.addAll(parse(parser, """
                {"type":"item.completed","item":{"id":"cmd-1","type":"command_execution","command":"rg CreateTask","aggregated_output":"service/video_sd.go:141"}}
                """));
        events.addAll(parse(parser, """
                {"type":"item.completed","item":{"id":"msg-1","type":"agent_message","text":"{\\\"schema\\\":\\\"ok\\\"}"}}
                """));

        assertEquals(List.of(ModelStreamEvent.Kind.TOOL_CALL, ModelStreamEvent.Kind.TOOL_RESULT,
                        ModelStreamEvent.Kind.RESPONSE),
                events.stream().map(ModelStreamEvent::kind).toList());
        assertEquals("rg CreateTask", events.get(0).content());
        assertEquals("{\"schema\":\"ok\"}", events.get(2).content());
    }

    private static List<ModelStreamEvent> parse(CodexStreamEventParser parser, String json) {
        return parser.accept(JsonParser.parseString(json).getAsJsonObject());
    }
}
