package com.archscope.jetbrains.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FunctionFlowRendererTest {
    @Test
    void rendersVerticalNavigableFunctionFlow() {
        String html = new FunctionFlowRenderer().render(JsonParser.parseString("""
                {"title":"Generate story","target_commit":"abc123",
                 "function_target":{"symbol":"Character.GenStoryBg","file":"controllers/character.go","line":605},
                 "nodes":[{"id":"root","kind":"entry","depth":0,"label":"Generate","symbol":"Character.GenStoryBg","file":"apps/chat/controllers/character.go","line":605},
                          {"id":"call","kind":"function","depth":1,"label":"Persist","symbol":"AddSDTask","file":"apps/chat/service/stable_diffusion.go","line":2471}],
                 "edges":[{"from":"root","to":"call","kind":"call","label":"create"}]}
                """).getAsJsonObject(), true);

        assertTrue(html.contains("detailOpen:true"));
        assertTrue(html.contains("data-action=\"detail-close\""));
        assertTrue(html.contains("在当前工作区打开源码"));
        assertTrue(html.contains("symbol:n.symbol||n.label||''"));
        assertTrue(html.contains("canvas.scrollTop=canvasScrollTop"));
        assertTrue(html.contains("addEventListener('pointermove'"));
        assertTrue(html.contains("data-action=\"zoom-out\""));
        assertTrue(html.contains("data-action=\"zoom-in\""));
        assertTrue(html.contains("function setZoom(value)"));
        assertTrue(html.contains("user-select:none"));
        assertTrue(html.contains("window.getSelection()?.removeAllRanges()"));
        assertTrue(html.contains("function componentRanks(ids)"));
        assertTrue(html.contains("if(ty>sy)"));
        assertTrue(html.contains("class=\"source-link\""));
        assertTrue(html.contains("function edgeCaption(e)"));
        assertTrue(html.contains("单次择一"));
        assertTrue(html.contains("顺序未标明"));
        assertFalse(html.contains("const levels=new Map()"));
        assertFalse(html.contains("adjacent=b.index===a.index+1"));
    }
}
