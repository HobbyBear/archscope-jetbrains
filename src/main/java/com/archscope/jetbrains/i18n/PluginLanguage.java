package com.archscope.jetbrains.i18n;

import com.archscope.jetbrains.model.AnalysisRequest;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PluginLanguage {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static volatile AnalysisRequest.OutputLanguage current = defaultLanguage();

    private PluginLanguage() {
    }

    public static AnalysisRequest.OutputLanguage current() {
        return current;
    }

    public static void use(AnalysisRequest.OutputLanguage language) {
        current = language == null ? defaultLanguage() : language;
    }

    public static boolean isEnglish() {
        return current.isEnglish();
    }

    public static String text(String chinese, String english) {
        return isEnglish() ? english : chinese;
    }

    public static String userMessage(String message) {
        if (!isEnglish() || message == null || !HAN.matcher(message).find()) return message;
        if (message.startsWith("架构报告校验失败")) {
            return "Report validation failed because the model response did not satisfy the required report schema. Retry the analysis; if it repeats, simplify conflicting custom instructions.";
        }
        if (message.contains("没有返回合法") && message.contains("JSON")) {
            return "The model did not return valid report JSON. Retry the analysis; if it repeats, simplify conflicting custom instructions.";
        }
        if (message.contains("schema 无效")) {
            return "The model response used an invalid report schema. Retry the analysis.";
        }
        if (message.contains("没有返回") && message.contains("业务流程")) {
            return "The model did not return a source-backed business flow. Broaden the analysis topic or add more specific custom instructions.";
        }
        int separator = message.indexOf('：');
        if (separator >= 0 && separator + 1 < message.length()) {
            String detail = message.substring(separator + 1).strip();
            if (!detail.isEmpty() && !HAN.matcher(detail).find()) return "Operation failed: " + detail;
        }
        return "The operation failed. See the IDE log for technical details.";
    }

    private static AnalysisRequest.OutputLanguage defaultLanguage() {
        return Locale.getDefault().getLanguage().equals(Locale.CHINESE.getLanguage())
                ? AnalysisRequest.OutputLanguage.CHINESE
                : AnalysisRequest.OutputLanguage.ENGLISH;
    }
}
