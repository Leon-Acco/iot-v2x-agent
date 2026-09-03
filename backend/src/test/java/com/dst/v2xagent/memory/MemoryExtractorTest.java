package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import com.dst.v2xagent.memory.model.MemoryQuery;
import com.dst.v2xagent.memory.model.WorkingMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆提取器单元测试：偏好 / 任务摘要 / 异常模式三类沉淀规则
 */
class MemoryExtractorTest {

    /** 桩 MemoryManager：捕获写入的记忆 */
    static class StubMemoryManager implements MemoryManager {
        final List<Memory> remembered = new ArrayList<>();

        @Override
        public WorkingMemory getWorkingMemory(String tenantId, Long userId, String sessionId) {
            return new WorkingMemory(tenantId, userId, sessionId, java.util.Map.of());
        }

        @Override
        public void saveWorkingMemory(WorkingMemory workingMemory) {}

        @Override
        public List<Memory> recall(MemoryQuery query) {
            return List.of();
        }

        @Override
        public Long remember(Memory memory) {
            remembered.add(memory);
            return (long) remembered.size();
        }

        @Override
        public void update(Memory memory) {}

        @Override
        public void forget(String layer, Long id) {}
    }

    /** 普通问答：只沉淀 EPISODE，不产生偏好 / 异常记忆 */
    @Test
    void plainTurnOnlyEpisode() {
        StubMemoryManager stub = new StubMemoryManager();
        MemoryExtractor extractor = new MemoryExtractor(stub);
        List<Long> ids = extractor.extractAndRemember("T1", 1L,
                "近 7 天告警次数", "共 120 次告警", "alarm_count_by_type", List.of());
        assertEquals(1, ids.size());
        assertEquals("EPISODE", stub.remembered.get(0).layer());
    }

    /** 偏好句式：额外沉淀高重要度 PREFERENCE */
    @Test
    void preferenceIsCaptured() {
        StubMemoryManager stub = new StubMemoryManager();
        MemoryExtractor extractor = new MemoryExtractor(stub);
        extractor.extractAndRemember("T1", 1L,
                "以后分析 F001 重点关注 BMS", "好的", "chat", List.of());
        assertTrue(stub.remembered.stream().anyMatch(m ->
                "PREFERENCE".equals(m.memoryType()) && m.importance() >= 0.9
                && "FLEET".equals(m.subjectType()) && "F001".equals(m.subjectId())));
    }

    /** 异常结论 + 车队实体：沉淀 FLEET/PATTERN */
    @Test
    void anomalyPatternIsCaptured() {
        StubMemoryManager stub = new StubMemoryManager();
        MemoryExtractor extractor = new MemoryExtractor(stub);
        extractor.extractAndRemember("T1", 1L,
                "F001 最近为什么故障这么多",
                "F001 电池告警异常飙升 81%，建议排查 BMS", "anomaly", List.of("r001"));
        assertTrue(stub.remembered.stream().anyMatch(m ->
                "PATTERN".equals(m.memoryType()) && "FLEET".equals(m.layer())
                && "F001".equals(m.subjectId())));
        assertTrue(stub.remembered.stream().anyMatch(m ->
                "EPISODE".equals(m.layer()) && m.resultRefs().contains("r001")));
    }
}
