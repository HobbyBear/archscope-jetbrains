package com.archscope.jetbrains.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class ModelClientRegistry {
    private ModelClientRegistry() {
    }

    public static ModelClient selected() {
        String selectedId = System.getProperty("archscope.modelProvider", "codex-local").strip();
        return selected(selectedId);
    }

    public static ModelClient selected(String selectedId) {
        for (ModelClient client : available()) {
            if (client.id().equals(selectedId)) return client;
        }
        throw new IllegalStateException("未找到模型供应商：" + selectedId);
    }

    public static List<ModelClient> available() {
        List<ModelClient> clients = new ArrayList<>();
        ServiceLoader.load(ModelClient.class, ModelClient.class.getClassLoader()).forEach(clients::add);
        if (clients.stream().noneMatch(client -> "codex-local".equals(client.id()))) {
            clients.add(new LocalCliModelClient());
        }
        if (clients.stream().noneMatch(client -> "claude-local".equals(client.id()))) {
            clients.add(new ClaudeCliModelClient());
        }
        return List.copyOf(clients);
    }
}
