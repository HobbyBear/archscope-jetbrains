package com.archscope.jetbrains.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

final class FunctionFlowPreviewTest {
    @Test
    @EnabledIfSystemProperty(named = "archscope.functionPreviewInput", matches = ".+")
    void rendersFunctionFlowPreview() throws Exception {
        Path input = Path.of(System.getProperty("archscope.functionPreviewInput")).toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("archscope.functionPreviewOutput")).toAbsolutePath().normalize();
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        String html = new FunctionFlowRenderer().render(
                JsonParser.parseString(Files.readString(input)).getAsJsonObject(), false);
        Files.writeString(output, html);
    }
}
