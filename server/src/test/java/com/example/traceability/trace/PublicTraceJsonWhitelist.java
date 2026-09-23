package com.example.traceability.trace;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者公开追溯响应的字段白名单断言（测试辅助）。
 * <p>
 * 递归遍历整个响应 JSON，按路径校验每一个字段名都属于公开白名单；数字型内部 ID、组织 / 场所 / 操作人字段、
 * 幂等键、摘要、扩展属性等只要以任何字段名出现就会失败，不依赖脆弱的取值匹配。
 * </p>
 */
public final class PublicTraceJsonWhitelist {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "$", Set.of("data", "meta"),
            "$.meta", Set.of("requestId", "timestamp"),
            "$.data", Set.of("publicTraceId", "product", "batch", "lineage", "timeline", "temperatureSummary",
                    "flowStatus", "riskStatus", "recallNotice", "queriedAt", "disclosure"),
            "$.data.product", Set.of("name", "category", "specification"),
            "$.data.batch", Set.of("publicBatchNo", "originType", "maskedOrigin", "productionDate"),
            "$.data.lineage", Set.of("nodes", "edges"),
            "$.data.lineage.nodes[]", Set.of("nodeKey", "generation", "role", "productName"),
            "$.data.lineage.edges[]", Set.of("fromNodeKey", "toNodeKey", "operationType", "occurredAt"),
            "$.data.timeline[]", Set.of("eventType", "event", "occurredAt", "dataSourceLabel", "nodeKey"),
            "$.data.temperatureSummary", Set.of("result", "ruleNote")
    );

    private PublicTraceJsonWhitelist() {
    }

    /**
     * 断言整个公开响应（含 data / meta 信封）只包含白名单字段。
     *
     * @param envelope 响应 JSON 根节点
     */
    public static void assertOnlyWhitelistedKeys(JsonNode envelope) {
        List<String> violations = new ArrayList<>();
        walk(envelope, "$", violations);
        assertThat(violations).as("公开追溯响应出现非白名单字段").isEmpty();
    }

    private static void walk(JsonNode node, String path, List<String> violations) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                walk(item, path + "[]", violations);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Set<String> allowed = ALLOWED.get(path);
        for (String name : node.propertyNames()) {
            if (allowed == null || !allowed.contains(name)) {
                violations.add(path + "." + name);
                continue;
            }
            walk(node.get(name), path + "." + name, violations);
        }
    }
}
