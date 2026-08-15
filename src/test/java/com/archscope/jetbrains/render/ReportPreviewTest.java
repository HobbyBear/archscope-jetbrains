package com.archscope.jetbrains.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

final class ReportPreviewTest {
    @Test
    @EnabledIfSystemProperty(named = "archscope.previewInput", matches = ".+")
    void rendersDarkPreview() throws Exception {
        Path input = Path.of(System.getProperty("archscope.previewInput")).toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("archscope.previewOutput")).toAbsolutePath().normalize();
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        boolean dark = Boolean.parseBoolean(System.getProperty("archscope.previewDark", "true"));
        String html = new ReportRenderer().render(
                JsonParser.parseString(Files.readString(input)).getAsJsonObject(),
                dark
        );
        Files.writeString(output, html);
    }
}
