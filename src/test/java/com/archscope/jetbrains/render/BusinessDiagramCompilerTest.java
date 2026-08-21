package com.archscope.jetbrains.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BusinessDiagramCompilerTest {
    @Test
    void compilesAValidatedFlowIntoDesktopAndMobileLayouts() {
        JsonObject report = JsonParser.parseString("""
                {"flow_map":{"id":"root","execution":"independent","children":[{
                  "id":"chat","title":"发送聊天消息","actor":"用户",
                  "trigger":"提交问题后调用 auditByAliyun(characterID, characterHisID, data) 进入自动审核",
                  "outcome":"审核状态和明细写入后异步通知全部下游消费者",
                  "data_subject":"聊天请求","entry_source":{"entry_kind":"route"},
                  "data_flow":[
                    {"step_id":"validate","phase":"validate","data":"问题","via":"call"},
                    {"step_id":"reply","phase":"deliver","data":"回答","via":"websocket"}],
                  "children":[
                    {"id":"validate","title":"校验请求","summary":"检查问题","kind":"decision","branches":[
                      {"label":"无效","meaning":"拒绝请求","outcome":"failure"},
                      {"label":"重试","meaning":"重新校验","outcome":"continue","target_step_id":"reply"}]},
                    {"id":"reply","title":"返回回答","summary":"发送到客户端","relation_kind":"websocket","branches":[]}]
                }]}}
                """).getAsJsonObject();

        JsonObject compiled = new BusinessDiagramCompiler().compile(report);
        JsonObject flow = compiled.getAsJsonArray("flows").get(0).getAsJsonObject();

        assertEquals("codebecause-business-diagram/v1", compiled.get("schema").getAsString());
        assertEquals("9/9", flow.getAsJsonObject("acceptance").get("checks_passed").getAsString()
                + "/" + flow.getAsJsonObject("acceptance").get("check_count").getAsString());
        assertTrue(flow.getAsJsonObject("layouts").has("desktop"));
        assertTrue(flow.getAsJsonObject("layouts").has("mobile"));
        assertEquals(0, flow.getAsJsonObject("acceptance").get("errors").getAsInt());
        JsonObject desktopBoxes = flow.getAsJsonObject("layouts").getAsJsonObject("desktop")
                .getAsJsonObject("boxes");
        assertTrue(desktopBoxes.getAsJsonObject("chat__entry").get("height").getAsDouble() > 104);
        assertTrue(desktopBoxes.getAsJsonObject("chat__outcome").get("height").getAsDouble() > 104);
    }
}
