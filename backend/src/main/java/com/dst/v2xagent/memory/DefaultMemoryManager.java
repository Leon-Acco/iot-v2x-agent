package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import com.dst.v2xagent.memory.model.MemoryQuery;
import com.dst.v2xagent.memory.model.WorkingMemory;
import com.dst.v2xagent.observability.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认记忆管理器：Working(Redis) + Episode(MySQL) + Semantic(MySQL FULLTEXT) 统一门面
 * Recall：Candidate(Exact/Recent/Semantic/Important) → 去重 → Rerank → TopK
 * Rerank 权重：重要度 0.5 + 时间最近度 0.3 + 文本命中 0.2
 */
@Component
@RequiredArgsConstructor
public class DefaultMemoryManager implements MemoryManager {

    private final WorkingMemoryStore workingMemoryStore;
    private final EpisodeMemoryStore episodeMemoryStore;
    private final SemanticMemoryStore semanticMemoryStore;

    @Override
    public WorkingMemory getWorkingMemory(String tenantId, Long userId, String sessionId) {
        return workingMemoryStore.load(tenantId, userId, sessionId)
                .orElse(new WorkingMemory(tenantId, userId, sessionId, Map.of()));
    }

    @Override
    public void saveWorkingMemory(WorkingMemory workingMemory) {
        workingMemoryStore.save(workingMemory);
    }

    @Override
    public List<Memory> recall(MemoryQuery query) {
        try (TraceContext.Span span = TraceContext.span("memory", "recall")) {
            List<Memory> candidates = new ArrayList<>();
            List<String> modes = query.effectiveModes();
            int topK = query.effectiveTopK();

            for (String layer : query.effectiveLayers()) {
                if ("EPISODE".equals(layer)) {
                    if (modes.contains("RECENT")) {
                        candidates.addAll(episodeMemoryStore.recent(query.tenantId(), query.userId(), topK));
                    }
                    if (modes.contains("IMPORTANT")) {
                        candidates.addAll(episodeMemoryStore.important(query.tenantId(), query.userId(), topK));
                    }
                    continue;
                }
                // SEMANTIC / VEHICLE / FLEET 都落在 agent_semantic_memory，按 subject_type 过滤
                String subjectType = switch (layer) {
                    case "VEHICLE" -> "VEHICLE";
                    case "FLEET" -> "FLEET";
                    default -> query.subjectType();
                };
                if (modes.contains("EXACT") && (query.subjectId() != null || subjectType != null)) {
                    candidates.addAll(semanticMemoryStore.bySubject(
                            query.tenantId(), subjectType, query.subjectId(), topK));
                }
                if (modes.contains("RECENT")) {
                    candidates.addAll(semanticMemoryStore.recent(query.tenantId(), subjectType, topK));
                }
                if (modes.contains("IMPORTANT")) {
                    candidates.addAll(semanticMemoryStore.important(query.tenantId(), subjectType, topK));
                }
                if (modes.contains("SEMANTIC") && query.text() != null && !query.text().isBlank()) {
                    candidates.addAll(semanticMemoryStore.fulltext(
                            query.tenantId(), subjectType, query.text(), topK));
                }
            }
            return rerank(candidates, query.text(), topK);
        }
    }

    @Override
    public Long remember(Memory memory) {
        if ("EPISODE".equals(memory.layer())) {
            Long userId = memory.subjectId() == null ? null : Long.valueOf(memory.subjectId());
            return episodeMemoryStore.insert(memory, userId, null);
        }
        return semanticMemoryStore.insert(memory);
    }

    @Override
    public void update(Memory memory) {
        semanticMemoryStore.update(memory);
    }

    @Override
    public void forget(String layer, Long id) {
        if ("EPISODE".equals(layer)) {
            episodeMemoryStore.delete(id);
        } else {
            semanticMemoryStore.delete(id);
        }
    }

    /** 去重 + 评分 + TopK：重要度 0.5 / 时间 0.3 / 文本命中 0.2 */
    private List<Memory> rerank(List<Memory> candidates, String text, int topK) {
        Map<Long, Memory> dedup = new LinkedHashMap<>();
        for (Memory m : candidates) {
            if (m.id() != null) {
                dedup.putIfAbsent(m.id(), m);
            }
        }
        return dedup.values().stream()
                .sorted(Comparator.comparingDouble((Memory m) -> score(m, text)).reversed())
                .limit(topK)
                .toList();
    }

    private double score(Memory m, String text) {
        double recency = 0;
        if (m.createdAt() != null) {
            long days = Duration.between(m.createdAt(), LocalDateTime.now()).toDays();
            recency = 1.0 / (1.0 + Math.max(0, days) / 7.0);
        }
        double textHit = 0;
        if (text != null && !text.isBlank() && m.content() != null) {
            for (String token : text.split("\\s+")) {
                if (token.length() >= 2 && m.content().contains(token)) {
                    textHit = 1;
                    break;
                }
            }
        }
        return m.importance() * 0.5 + recency * 0.3 + textHit * 0.2;
    }
}
