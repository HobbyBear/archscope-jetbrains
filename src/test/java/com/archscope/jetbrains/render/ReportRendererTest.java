package com.archscope.jetbrains.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

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
        assertTrue(html.contains("cursor:grab"));
        assertTrue(html.contains("user-select:none"));
        assertTrue(html.contains("window.getSelection()?.removeAllRanges()"));
        assertTrue(html.contains("addEventListener('pointermove'"));
        assertTrue(html.contains("canvasDrag.canvas.scrollLeft"));
        assertTrue(html.contains("data-action=\"zoom-out\""));
        assertTrue(html.contains("data-action=\"zoom-in\""));
        assertTrue(html.contains("function applyDomainZoom()"));
        assertTrue(html.contains("function setZoom(value)"));
        assertTrue(html.contains("Math.min(1.8,Math.max(.5"));
        assertTrue(html.contains("!IS_DOMAIN||!e.ctrlKey"));
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
        assertTrue(html.contains("function dataLineage(root)"));
        assertTrue(html.contains("主数据流"));
        assertTrue(html.contains("辅助输入汇入"));
        assertTrue(html.contains("后续独立消费"));
        assertTrue(html.contains("lineage-hop"));
        assertTrue(html.contains("本步 ${orderedOrigins.length} 个来源按源码顺序汇入"));
        assertFalse(html.contains("Number(a.line||0)-Number(b.line||0)"));
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
        assertTrue(html.contains("e.key==='Enter'&&!e.shiftKey"));
        assertTrue(html.contains("!e.isComposing"));
        assertFalse(html.contains("(e.ctrlKey||e.metaKey)&&e.key==='Enter'"));
        assertTrue(html.contains("list.slice(0,6)"));
        assertFalse(html.contains("typeof x==='object'&&x.target_id"));
        assertFalse(html.contains("x.outcome!=='success'&&x.outcome!=='continue'"));
        assertFalse(html.contains("一次看清触发如何分流"));
        assertTrue(html.contains("function businessDiagram(root)"));
        assertTrue(html.contains("class=\"flow-diagram\""));
        assertTrue(html.contains("class=\"diagram-shell\""));
        assertTrue(html.contains("class=\"phase-frame\""));
        assertTrue(html.contains("id=\"diagram-grid\""));
        assertTrue(html.contains("branch.target_id?stepPositions.get"));
        assertTrue(html.contains("item.main_path_label"));
        assertTrue(html.contains("END-TO-END BUSINESS FLOW"));
        assertTrue(html.contains("marker-end=\"url(#journey-arrow)\""));
        assertTrue(html.contains("class=\"branch-edge\""));
        assertTrue(html.contains("className='journey-map'"));
        assertTrue(html.contains("item.flowData"));
        assertFalse(html.contains("slice(0,n-1)+'…'"));
        assertTrue(html.contains("class=\"source-link\""));
        assertTrue(html.contains("text-decoration:underline"));
        assertFalse(html.contains("<code data-source='${payload}'"));
        assertTrue(html.contains("identity:'身份标识'"));
        assertTrue(html.contains("model_provider_name"));
        assertTrue(html.contains("已应用 System Prompt"));
        assertTrue(html.contains("function sourcePayload(x)"));
        assertFalse(html.contains("commit:reportCommit()"));
        assertTrue(html.contains("originCommit:reportCommit()"));
        assertTrue(html.contains("在当前工作区打开源码"));
        assertFalse(html.contains("IS_DOMAIN?'':REPORT.comparison?.target_commit"));
    }

    @Test
    void opensBusinessReportsOnTheCompleteFlow() {
        String html = new ReportRenderer().render(JsonParser.parseString("""
                {"title":"聊天逻辑","comparison":{"mode":"current_snapshot"},
                 "business_overview":{"purpose":"完成聊天"},"business_domains":[],
                 "flow_map":{"id":"root","execution":"independent","children":[]}}
                """).getAsJsonObject(), true);

        assertTrue(html.contains("IS_DOMAIN=REPORT.comparison?.mode==='current_snapshot'"));
        assertTrue(html.contains("view:'architecture'"));
        assertTrue(html.contains("当前 HEAD"));
        assertTrue(html.contains("白话走一遍"));
        assertFalse(html.contains("__REPORT_JSON__"));
    }

    @Test
    void acceptsTheIdeThemeAsTheInitialTheme() {
        String html = new ReportRenderer().render(JsonParser.parseString("{\"title\":\"Test\"}").getAsJsonObject(), true);

        assertTrue(html.contains("DEFAULT_THEME='dark'"));
    }

    @Test
    void rendersEnglishReportsWithoutChineseUiText() {
        String html = new ReportRenderer().render(JsonParser.parseString("""
                {"title":"Chat logic","summary":"How a request becomes a streamed response",
                 "output_language":"en","comparison":{"mode":"current_snapshot"},
                 "business_overview":{"purpose":"Explain chat behavior"},"business_domains":[],
                 "flow_map":{"id":"root","execution":"independent","children":[]}}
                """).getAsJsonObject());

        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("Business overview"));
        assertTrue(html.contains("Complete flows"));
        assertFalse(html.matches("(?s).*[\\p{IsHan}].*"), () -> Pattern.compile("[\\p{IsHan}]+")
                .matcher(html).results().map(java.util.regex.MatchResult::group).distinct().toList().toString());
    }
}
