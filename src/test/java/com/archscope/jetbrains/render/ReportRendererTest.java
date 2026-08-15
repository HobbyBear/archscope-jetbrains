package com.archscope.jetbrains.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReportRendererTest {
    @Test
    void rendersSelfContainedReportAndEscapesClosingScript() {
        String html = new ReportRenderer().render(JsonParser.parseString("{\"title\":\"</script>\"}").getAsJsonObject());
        assertTrue(html.contains("AI Code Review"));
        assertTrue(html.contains("<\\/script>"));
        assertTrue(html.contains("workspace ${state.detail?'':'detail-closed'}"));
        assertTrue(html.contains("detail:false"));
        assertTrue(html.contains("@media(max-width:880px)"));
        assertTrue(html.contains("class=\"close-detail\""));
        assertTrue(html.contains("<details class=\"fold\""));
        assertFalse(html.contains("background-image:linear-gradient"));
        assertTrue(html.contains("只看本次改动"));
        assertTrue(html.contains("完整业务流程"));
        assertTrue(html.contains("change_status"));
        assertTrue(html.contains("data-flow-mode"));
        assertTrue(html.contains("canvas.scrollTop=canvasScrollTop"));
        assertTrue(html.contains("canvas.scrollLeft=canvasScrollLeft"));
        assertTrue(html.contains("data-business-flow"));
        assertTrue(html.contains("businessFlows=FLOW.execution==='independent'"));
        assertTrue(html.contains("class=\"empty-state\""));
        assertTrue(html.contains("data-refine-input"));
        assertTrue(html.contains("archscopeRefineReport"));
        assertTrue(html.contains("business_overview"));
        assertTrue(html.contains("stateEffects"));
        assertTrue(html.contains("card-source"));
        assertTrue(html.contains("本流程只跟踪一条主数据故事"));
        assertTrue(html.contains("data_origins"));
        assertTrue(html.contains("dataStageBrief"));
        assertTrue(html.contains("本步 ${orderedOrigins.length} 个来源按源码顺序汇入"));
        assertFalse(html.contains("Number(a.line||0)-Number(b.line||0)"));
        assertTrue(html.contains("x.supporting_sources"));
        assertTrue(html.contains("稍后独立读取 · 不属于本次执行"));
        assertFalse(html.contains(".origin-row"));
        assertFalse(html.contains(".hop-row"));
        assertTrue(html.contains("业务总览"));
        assertTrue(html.contains("完整流程"));
        assertTrue(html.contains(".rail .overview span{display:none}"));
        assertFalse(html.contains("Evidence-backed review"));
        assertFalse(html.contains("Selected commits"));
        assertFalse(html.contains("__DEFAULT_THEME__"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("http://"));
        assertTrue(html.contains("e.key==='Enter'&&!e.shiftKey"));
        assertTrue(html.contains("!e.isComposing"));
        assertFalse(html.contains("(e.ctrlKey||e.metaKey)&&e.key==='Enter'"));
        assertTrue(html.contains("list.slice(0,6)"));
        assertFalse(html.contains("typeof x==='object'&&x.target_id"));
        assertFalse(html.contains("x.outcome!=='success'&&x.outcome!=='continue'"));
        assertTrue(html.contains("一次看清触发如何分流"));
        assertTrue(html.contains("class=\"source-link\""));
        assertTrue(html.contains("text-decoration:underline"));
        assertTrue(html.contains("class=\"object-sources\""));
        assertFalse(html.contains("<code data-source='${payload}'"));
        assertTrue(html.contains("identity:'身份标识'"));
    }

    @Test
    void opensBusinessReportsOnTheDomainOverview() {
        String html = new ReportRenderer().render(JsonParser.parseString("""
                {"title":"聊天逻辑","comparison":{"mode":"current_snapshot"},
                 "business_overview":{"purpose":"完成聊天"},"business_domains":[],
                 "flow_map":{"id":"root","execution":"independent","children":[]}}
                """).getAsJsonObject(), true);

        assertTrue(html.contains("IS_DOMAIN=REPORT.comparison?.mode==='current_snapshot'"));
        assertTrue(html.contains("view:IS_DOMAIN?'overview':'architecture'"));
        assertTrue(html.contains("当前 HEAD"));
        assertTrue(html.contains("参与者"));
        assertFalse(html.contains("__REPORT_JSON__"));
    }

    @Test
    void acceptsTheIdeThemeAsTheInitialTheme() {
        String html = new ReportRenderer().render(JsonParser.parseString("{\"title\":\"Test\"}").getAsJsonObject(), true);

        assertTrue(html.contains("DEFAULT_THEME='dark'"));
    }
}
