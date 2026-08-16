package com.archscope.jetbrains.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ModelJsonParserTest {
    @Test
    void extractsOneObjectFromMarkdownAndTrailingCommentary() {
        var parsed = ModelJsonParser.parseObject("result:\n```json\n{\"schema\":\"ok\",\"nested\":{}}\n```\ndone");

        assertEquals("ok", parsed.get("schema").getAsString());
    }

    @Test
    void acceptsLenientJsonThatIsStillStructurallyComplete() {
        var parsed = ModelJsonParser.parseObject("{'schema':'ok'}");

        assertEquals("ok", parsed.get("schema").getAsString());
    }

    @Test
    void doesNotPretendThatATruncatedObjectIsComplete() {
        assertThrows(RuntimeException.class, () -> ModelJsonParser.parseObject("{\"schema\":\"ok\",\"flows\":["));
    }
}
