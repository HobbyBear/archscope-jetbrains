package com.archscope.jetbrains.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

record BusinessDiagramSpec(
        String flowId,
        String title,
        String qualityProfile,
        List<Node> nodes,
        List<Edge> edges,
        List<String> mainPath,
        Map<String, Layout> layouts,
        Acceptance acceptance
) {
    record Node(String id, String sourceId, String kind, String phase, String label, String summary,
                String data, String via, String outcome) { }

    record Edge(String id, String from, String to, String role, String label) { }

    record Box(double x, double y, double width, double height) { }

    record Route(List<List<Double>> points, double labelX, double labelY) { }

    record Frame(String label, double y, double height) { }

    record Layout(double width, double height, Map<String, Box> boxes, Map<String, Route> routes,
                  List<Frame> frames) { }

    record Acceptance(int checksPassed, int checkCount, int errors, int warnings, List<String> diagnostics) { }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("flow_id", flowId);
        root.addProperty("title", title);
        root.addProperty("quality_profile", qualityProfile);
        JsonArray nodeArray = new JsonArray();
        for (Node node : nodes) {
            JsonObject item = new JsonObject();
            item.addProperty("id", node.id());
            item.addProperty("source_id", node.sourceId());
            item.addProperty("kind", node.kind());
            item.addProperty("phase", node.phase());
            item.addProperty("label", node.label());
            item.addProperty("summary", node.summary());
            item.addProperty("data", node.data());
            item.addProperty("via", node.via());
            item.addProperty("outcome", node.outcome());
            nodeArray.add(item);
        }
        root.add("nodes", nodeArray);
        JsonArray edgeArray = new JsonArray();
        for (Edge edge : edges) {
            JsonObject item = new JsonObject();
            item.addProperty("id", edge.id());
            item.addProperty("from", edge.from());
            item.addProperty("to", edge.to());
            item.addProperty("role", edge.role());
            item.addProperty("label", edge.label());
            edgeArray.add(item);
        }
        root.add("edges", edgeArray);
        root.add("main_path", strings(mainPath));
        JsonObject layoutObject = new JsonObject();
        layouts.forEach((name, layout) -> layoutObject.add(name, layoutJson(layout)));
        root.add("layouts", layoutObject);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("checks_passed", acceptance.checksPassed());
        receipt.addProperty("check_count", acceptance.checkCount());
        receipt.addProperty("errors", acceptance.errors());
        receipt.addProperty("warnings", acceptance.warnings());
        receipt.add("diagnostics", strings(acceptance.diagnostics()));
        root.add("acceptance", receipt);
        return root;
    }

    private JsonObject layoutJson(Layout layout) {
        JsonObject result = new JsonObject();
        result.addProperty("width", layout.width());
        result.addProperty("height", layout.height());
        JsonObject boxes = new JsonObject();
        layout.boxes().forEach((id, box) -> {
            JsonObject value = new JsonObject();
            value.addProperty("x", box.x());
            value.addProperty("y", box.y());
            value.addProperty("width", box.width());
            value.addProperty("height", box.height());
            boxes.add(id, value);
        });
        result.add("boxes", boxes);
        JsonObject routes = new JsonObject();
        layout.routes().forEach((id, route) -> {
            JsonObject value = new JsonObject();
            JsonArray points = new JsonArray();
            route.points().forEach(point -> {
                JsonArray pair = new JsonArray();
                pair.add(point.get(0));
                pair.add(point.get(1));
                points.add(pair);
            });
            value.add("points", points);
            value.addProperty("label_x", route.labelX());
            value.addProperty("label_y", route.labelY());
            routes.add(id, value);
        });
        result.add("routes", routes);
        JsonArray frames = new JsonArray();
        for (Frame frame : layout.frames()) {
            JsonObject value = new JsonObject();
            value.addProperty("label", frame.label());
            value.addProperty("y", frame.y());
            value.addProperty("height", frame.height());
            frames.add(value);
        }
        result.add("frames", frames);
        return result;
    }

    private JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }
}
