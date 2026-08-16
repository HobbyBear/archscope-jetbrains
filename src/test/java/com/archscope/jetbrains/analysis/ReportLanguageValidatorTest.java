package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ReportLanguageValidatorTest {
    @Test
    void englishReportAllowsOriginalChineseTechnicalIdentifiers() {
        var report = JsonParser.parseString("""
                {"title":"Chat workflow","nodes":[{"file":"src/聊天/入口.go","symbol":"处理消息"}]}
                """).getAsJsonObject();

        assertDoesNotThrow(() -> ReportLanguageValidator.validate(report, AnalysisRequest.OutputLanguage.ENGLISH));
    }

    @Test
    void englishReportStillRejectsChineseHumanReadableContent() {
        var report = JsonParser.parseString("""
                {"title":"聊天流程","nodes":[{"file":"src/chat.go","symbol":"handleChat"}]}
                """).getAsJsonObject();

        assertThrows(ModelClientException.class,
                () -> ReportLanguageValidator.validate(report, AnalysisRequest.OutputLanguage.ENGLISH));
    }
}
