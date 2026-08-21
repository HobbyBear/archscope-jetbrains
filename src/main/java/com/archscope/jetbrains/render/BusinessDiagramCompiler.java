package com.archscope.jetbrains.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BusinessDiagramCompiler {
    private static final int CHECK_COUNT = 9;

    JsonObject compile(JsonObject report) {
        JsonObject result = new JsonObject();
        JsonArray flows = new JsonArray();
        JsonObject root = object(report, "flow_map");
        boolean english = "en".equals(string(report, "output_language"));
        if (root != null) {
            JsonArray declared = array(root, "children");
            if ("independent".equals(string(root, "execution")) && declared != null && !declared.isEmpty()) {
                for (JsonElement element : declared) if (element.isJsonObject()) flows.add(compileFlow(element.getAsJsonObject(), english).toJson());
            } else if (array(root, "children") != null && !array(root, "children").isEmpty()) {
                flows.add(compileFlow(root, english).toJson());
            }
        }
        result.addProperty("schema", "codebecause-business-diagram/v1");
        result.add("flows", flows);
        return result;
    }

    private BusinessDiagramSpec compileFlow(JsonObject flow, boolean english) {
        JsonArray steps = array(flow, "children");
        List<JsonObject> stepObjects = new ArrayList<>();
        if (steps != null) for (JsonElement element : steps) if (element.isJsonObject()) stepObjects.add(element.getAsJsonObject());
        Map<String, JsonObject> hops = new HashMap<>();
        JsonArray dataFlow = array(flow, "data_flow");
        if (dataFlow != null) for (JsonElement element : dataFlow) {
            if (element.isJsonObject()) hops.put(string(element.getAsJsonObject(), "step_id"), element.getAsJsonObject());
        }

        String flowId = fallback(string(flow, "id"), "flow");
        List<BusinessDiagramSpec.Node> nodes = new ArrayList<>();
        List<BusinessDiagramSpec.Edge> edges = new ArrayList<>();
        List<String> mainPath = new ArrayList<>();
        String entryId = flowId + "__entry";
        nodes.add(new BusinessDiagramSpec.Node(entryId, flowId, "entry", english ? "Entry" : "入口",
                fallback(string(flow, "trigger"), string(flow, "title")), fallback(string(flow, "actor"), english ? "Caller" : "业务调用方"),
                string(flow, "data_subject"), string(object(flow, "entry_source"), "entry_kind"), ""));
        mainPath.add(entryId);
        for (JsonObject step : stepObjects) {
            String id = string(step, "id");
            JsonObject hop = hops.get(id);
            String phaseKey = string(hop, "phase");
            String kind = nodeKind(step, phaseKey);
            nodes.add(new BusinessDiagramSpec.Node(id, id, kind, phaseName(phaseKey, english),
                    fallback(string(step, "title"), english ? "Business step" : "业务处理"), string(step, "summary"),
                    string(hop, "data"), fallback(string(hop, "via"), string(step, "relation_kind")), ""));
            mainPath.add(id);
        }
        String outcomeId = flowId + "__outcome";
        nodes.add(new BusinessDiagramSpec.Node(outcomeId, flowId, "outcome", english ? "Return" : "返回",
                fallback(string(flow, "outcome"), fallback(string(flow, "end_title"), english ? "Business result" : "业务结果")),
                (english ? "Returned to " : "返回给 ") + fallback(string(flow, "actor"), english ? "caller" : "业务调用方"),
                stepObjects.isEmpty() ? string(flow, "data_subject") : string(hops.get(string(stepObjects.get(stepObjects.size() - 1), "id")), "data"),
                "", "success"));
        mainPath.add(outcomeId);

        for (int index = 0; index < mainPath.size() - 1; index++) {
            String from = mainPath.get(index);
            String to = mainPath.get(index + 1);
            JsonObject sourceStep = index > 0 && index <= stepObjects.size() ? stepObjects.get(index - 1) : null;
            String label = sourceStep == null
                    ? fallback(string(object(flow, "entry_source"), "entry_kind"), english ? "enter" : "进入")
                    : fallback(string(sourceStep, "main_path_label"), fallback(string(hops.get(string(sourceStep, "id")), "via"), english ? "continue" : "继续"));
            edges.add(new BusinessDiagramSpec.Edge("main-" + index, from, to, "main", label));
        }
        for (JsonObject step : stepObjects) {
            JsonArray branches = array(step, "branches");
            if (branches == null) continue;
            for (int index = 0; index < branches.size(); index++) {
                if (!branches.get(index).isJsonObject()) continue;
                JsonObject branch = branches.get(index).getAsJsonObject();
                String branchId = string(step, "id") + "__branch_" + index;
                String outcome = fallback(string(branch, "outcome"), "failure");
                nodes.add(new BusinessDiagramSpec.Node(branchId, string(step, "id"), "branch", english ? "Branch" : "分支",
                        fallback(string(branch, "label"), english ? "Branch" : "分支"), string(branch, "meaning"), "", "", outcome));
                edges.add(new BusinessDiagramSpec.Edge("branch-" + branchId, string(step, "id"), branchId, "branch", string(branch, "label")));
                String target = string(branch, "target_step_id");
                if (!target.isBlank()) edges.add(new BusinessDiagramSpec.Edge("rejoin-" + branchId, branchId, target, "rejoin", english ? "rejoin" : "汇回"));
            }
        }

        List<String> diagnostics = validate(nodes, edges, mainPath);
        Map<String, BusinessDiagramSpec.Layout> layouts = Map.of(
                "desktop", layout(nodes, edges, mainPath, false),
                "mobile", layout(nodes, edges, mainPath, true)
        );
        layouts.forEach((name, layout) -> diagnostics.addAll(validateLayout(name, layout, nodes, edges)));
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Business diagram validation failed: " + String.join("; ", diagnostics));
        return new BusinessDiagramSpec(flowId, string(flow, "title"), "presentation", List.copyOf(nodes), List.copyOf(edges),
                List.copyOf(mainPath), layouts, new BusinessDiagramSpec.Acceptance(CHECK_COUNT, CHECK_COUNT, 0, 0, List.of()));
    }

    private List<String> validate(List<BusinessDiagramSpec.Node> nodes, List<BusinessDiagramSpec.Edge> edges, List<String> mainPath) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (BusinessDiagramSpec.Node node : nodes) if (node.id().isBlank() || !ids.add(node.id())) errors.add("diagram/node-id: " + node.id());
        if (mainPath.size() < 2) errors.add("diagram/main-path-empty");
        if (!ids.containsAll(mainPath)) errors.add("diagram/main-path-reference");
        Set<String> edgeIds = new HashSet<>();
        for (BusinessDiagramSpec.Edge edge : edges) {
            if (!edgeIds.add(edge.id())) errors.add("diagram/edge-id: " + edge.id());
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) errors.add("diagram/edge-reference: " + edge.id());
            if (edge.from().equals(edge.to())) errors.add("diagram/self-edge: " + edge.id());
        }
        for (int index = 0; index < mainPath.size() - 1; index++) {
            String from = mainPath.get(index), to = mainPath.get(index + 1);
            if (edges.stream().noneMatch(edge -> "main".equals(edge.role()) && from.equals(edge.from()) && to.equals(edge.to()))) {
                errors.add("diagram/main-path-gap: " + from + " -> " + to);
            }
        }
        if (mainPath.size() > 26) errors.add("diagram/main-path-too-large: " + mainPath.size());
        return errors;
    }

    private BusinessDiagramSpec.Layout layout(List<BusinessDiagramSpec.Node> nodes, List<BusinessDiagramSpec.Edge> edges,
                                               List<String> mainPath, boolean mobile) {
        double width = mobile ? 320 : 1080, mainWidth = mobile ? 250 : 280, branchWidth = mobile ? 118 : 230;
        double mainX = (width - mainWidth) / 2, cursorY = 60;
        Map<String, BusinessDiagramSpec.Box> boxes = new LinkedHashMap<>();
        Map<String, BusinessDiagramSpec.Node> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        for (String id : mainPath) {
            BusinessDiagramSpec.Node node = byId.get(id);
            double height = mainNodeHeight(node, mobile);
            boxes.put(id, new BusinessDiagramSpec.Box(mainX, cursorY, mainWidth, height));
            long branches = edges.stream().filter(edge -> "branch".equals(edge.role()) && id.equals(edge.from())).count();
            double rows = Math.ceil(branches / 2.0);
            cursorY += mobile && branches > 0
                    ? height + 50 + rows * 108
                    : height + 82 + Math.max(0, rows - 1) * 82;
        }
        for (String sourceId : mainPath) {
            List<BusinessDiagramSpec.Edge> branches = edges.stream().filter(edge -> "branch".equals(edge.role()) && sourceId.equals(edge.from())).toList();
            BusinessDiagramSpec.Box source = boxes.get(sourceId);
            for (int index = 0; index < branches.size(); index++) {
                boolean left = index % 2 == 0;
                double height = mobile ? 96 : 66;
                double x = mobile ? (left ? 8 : width - 8 - branchWidth) : (left ? 54 : width - 54 - branchWidth);
                double y = mobile
                        ? source.y() + source.height() + 16 + Math.floor(index / 2.0) * (height + 12)
                        : source.y() + Math.floor(index / 2.0) * (height + 16) + (source.height() - height) / 2;
                boxes.put(branches.get(index).to(), new BusinessDiagramSpec.Box(x, y, branchWidth, height));
            }
        }
        Map<String, BusinessDiagramSpec.Route> routes = new LinkedHashMap<>();
        for (BusinessDiagramSpec.Edge edge : edges) {
            BusinessDiagramSpec.Box from = boxes.get(edge.from()), to = boxes.get(edge.to());
            if (from == null || to == null) continue;
            List<List<Double>> points;
            if ("main".equals(edge.role())) {
                double x = from.x() + from.width() / 2;
                points = List.of(point(x, from.y() + from.height()), point(x, to.y()));
            } else if ("branch".equals(edge.role())) {
                boolean left = to.x() < from.x();
                double sx = left ? from.x() : from.x() + from.width(), tx = left ? to.x() + to.width() : to.x(), ty = to.y() + to.height() / 2;
                points = List.of(point(sx, from.y() + from.height() / 2), point((sx + tx) / 2, from.y() + from.height() / 2), point((sx + tx) / 2, ty), point(tx, ty));
            } else {
                boolean left = from.x() < width / 2;
                double outer = left ? 4 : width - 4, targetX = left ? to.x() : to.x() + to.width();
                points = List.of(point(left ? from.x() : from.x() + from.width(), from.y() + from.height()), point(outer, from.y() + from.height()), point(outer, to.y() + to.height() / 2), point(targetX, to.y() + to.height() / 2));
            }
            List<Double> middle = points.get(points.size() / 2);
            double labelX = middle.get(0), labelY = middle.get(1) - 10;
            if ("rejoin".equals(edge.role())) {
                List<Double> previous = points.get(points.size() - 2), last = points.get(points.size() - 1);
                labelX = (previous.get(0) + last.get(0)) / 2;
                labelY = last.get(1) - 10;
            }
            labelX = Math.max(42, Math.min(width - 42, labelX));
            routes.put(edge.id(), new BusinessDiagramSpec.Route(points, labelX, labelY));
        }
        return new BusinessDiagramSpec.Layout(width, cursorY + 18, boxes, routes, List.of());
    }

    private double mainNodeHeight(BusinessDiagramSpec.Node node, boolean mobile) {
        int titleCapacity = mobile ? 32 : 38;
        int proseCapacity = mobile ? 42 : 48;
        if ("decision".equals(node.kind())) {
            double estimated = 36 + 13 + 8 + visualLines(node.label(), titleCapacity) * 16.0;
            return Math.max(126, Math.min(196, estimated));
        }
        double estimated = 24 + 13 + 8
                + visualLines(node.label(), titleCapacity) * 16.0
                + visualLines(node.summary(), proseCapacity) * 14.0;
        String data = String.join(" · ", List.of(node.data(), node.via()).stream()
                .filter(value -> value != null && !value.isBlank()).toList());
        if (!data.isBlank()) estimated += 5 + visualLines(data, mobile ? 44 : 52) * 12.0;
        return Math.max(104, Math.min(196, estimated + 18));
    }

    private int visualLines(String text, int capacity) {
        if (text == null || text.isBlank()) return 0;
        int units = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            units += value <= 0x7f ? 1 : 2;
        }
        return Math.max(1, (int) Math.ceil(units / (double) capacity));
    }

    private List<String> validateLayout(String name, BusinessDiagramSpec.Layout layout,
                                        List<BusinessDiagramSpec.Node> nodes, List<BusinessDiagramSpec.Edge> edges) {
        List<String> errors = new ArrayList<>();
        for (BusinessDiagramSpec.Node node : nodes) {
            BusinessDiagramSpec.Box box = layout.boxes().get(node.id());
            if (box == null) {
                errors.add("diagram/layout-missing-box[" + name + "]: " + node.id());
            } else if (box.x() < 0 || box.y() < 0 || box.x() + box.width() > layout.width()
                    || box.y() + box.height() > layout.height()) {
                errors.add("diagram/layout-out-of-bounds[" + name + "]: " + node.id());
            }
        }
        List<Map.Entry<String, BusinessDiagramSpec.Box>> boxes = new ArrayList<>(layout.boxes().entrySet());
        for (int left = 0; left < boxes.size(); left++) {
            for (int right = left + 1; right < boxes.size(); right++) {
                if (overlaps(boxes.get(left).getValue(), boxes.get(right).getValue())) {
                    errors.add("diagram/node-overlap[" + name + "]: " + boxes.get(left).getKey() + "/" + boxes.get(right).getKey());
                }
            }
        }
        for (BusinessDiagramSpec.Edge edge : edges) {
            BusinessDiagramSpec.Route route = layout.routes().get(edge.id());
            if (route == null || route.points().size() < 2) {
                errors.add("diagram/route-missing[" + name + "]: " + edge.id());
                continue;
            }
            for (int index = 1; index < route.points().size(); index++) {
                List<Double> previous = route.points().get(index - 1), current = route.points().get(index);
                if (!previous.get(0).equals(current.get(0)) && !previous.get(1).equals(current.get(1))) {
                    errors.add("diagram/route-not-orthogonal[" + name + "]: " + edge.id());
                }
            }
        }
        return errors;
    }

    private boolean overlaps(BusinessDiagramSpec.Box left, BusinessDiagramSpec.Box right) {
        return left.x() < right.x() + right.width() && left.x() + left.width() > right.x()
                && left.y() < right.y() + right.height() && left.y() + left.height() > right.y();
    }

    private List<Double> point(double x, double y) { return List.of(x, y); }

    private String nodeKind(JsonObject step, String phase) {
        if (array(step, "branches") != null && !array(step, "branches").isEmpty() || "decision".equals(string(step, "kind"))) return "decision";
        if ("persist".equals(phase) || oneOf(string(step, "relation_kind"), "sql", "writes")) return "data";
        if (oneOf(string(step, "relation_kind"), "http", "websocket", "event")) return "external";
        return "process";
    }

    private String phaseName(String phase, boolean english) {
        return switch (phase) {
            case "ingest" -> english ? "Ingest" : "进入";
            case "validate" -> english ? "Validate" : "校验";
            case "persist" -> english ? "Persist" : "落地";
            case "deliver" -> english ? "Deliver" : "交付";
            default -> english ? "Transform" : "处理";
        };
    }

    private boolean oneOf(String value, String... allowed) { for (String item : allowed) if (item.equals(value)) return true; return false; }
    private String fallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private JsonObject object(JsonObject owner, String name) { return owner != null && owner.has(name) && owner.get(name).isJsonObject() ? owner.getAsJsonObject(name) : null; }
    private JsonArray array(JsonObject owner, String name) { return owner != null && owner.has(name) && owner.get(name).isJsonArray() ? owner.getAsJsonArray(name) : null; }
    private String string(JsonObject owner, String name) { return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive() ? owner.get(name).getAsString() : ""; }
}
