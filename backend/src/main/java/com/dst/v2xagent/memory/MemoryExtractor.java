package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆提取器：Agent 完成任务后，判断哪些信息值得沉淀
 * 规则驱动（不调模型，确定性可测试）：
 * 1. 用户偏好（“以后 / 重点关注 / 记住”）→ SEMANTIC/PREFERENCE，importance 0.95
 * 2. 任务摘要→ EPISODE（实体 + 结果引用）
 * 3. 异常结论（含百分比的异常描述）→ 主体语义记忆（PATTERN）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractor {

    private final MemoryManager memoryManager;

    /** 用户偏好触发词 */
    private static final Pattern PREFERENCE = Pattern.compile(
            "(以后|下次|重点关注|记住|别忘了)");
    /** 异常结论特征：百分比 + 异常词 */
    private static final Pattern ANOMALY = Pattern.compile(
            "(异常|飙升|突增|明显高于).*%|%.*(异常|飙升|突增)");
    /** 车队编号 */
    private static final Pattern FLEET = Pattern.compile("F\\d{3}");
    /** VIN（17 位） */
    private static final Pattern VIN = Pattern.compile("\b[A-HJ-NPR-Z0-9]{17}\b");

    /**
     * 从一次完整对话提取并沉淀记忆。
     * @param tenantId   租户
     * @param userId     用户 id
     * @param question   用户问题
     * @param conclusion Agent 结论
     * @param taskType   任务类型（capability id / 路由标签）
     * @param resultRefs 结果引用（result_id）
     */
    public List<Long> extractAndRemember(String tenantId, Long userId, String question,
                                         String conclusion, String taskType, List<String> resultRefs) {
        List<Long> ids = new ArrayList<>();
        try {
            Map<String, Object> entities = extractEntities(question + " " + conclusion);

            // 1 用户偏好：高重要度沉淀
            if (question != null && PREFERENCE.matcher(question).find()) {
                String fleet = entities.get("fleet") == null ? null : String.valueOf(entities.get("fleet"));
                Long id = memoryManager.remember(Memory.of("SEMANTIC", tenantId,
                        fleet != null ? "FLEET" : "USER", fleet != null ? fleet : String.valueOf(userId),
                        "PREFERENCE", question, 0.95, 0.9, "extractor"));
                if (id != null) {
                    ids.add(id);
                }
            }

            // 2 任务摘要→ EPISODE
            String summary = buildSummary(question, conclusion);
            Memory episode = new Memory(null, "EPISODE", tenantId, "USER", String.valueOf(userId),
                    taskType == null ? "TASK" : taskType, summary, 0.5, 1.0, "extractor",
                    resultRefs == null ? List.of() : resultRefs, entities, null);
            Long episodeId = memoryManager.remember(episode);
            if (episodeId != null) {
                ids.add(episodeId);
            }

            // 3 异常结论→ 主体 PATTERN 记忆
            if (conclusion != null && ANOMALY.matcher(conclusion).find()) {
                String fleet = entities.get("fleet") == null ? null : String.valueOf(entities.get("fleet"));
                if (fleet != null) {
                    String content = conclusion.length() > 300 ? conclusion.substring(0, 300) : conclusion;
                    Long id = memoryManager.remember(Memory.of("FLEET", tenantId, "FLEET", fleet,
                            "PATTERN", content, 0.8, 0.7, "anomaly_analysis"));
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("记忆提取失败（不影响主链路）: {}", e.getMessage());
        }
        return ids;
    }

    /** 实体提取：车队 + VIN */
    private Map<String, Object> extractEntities(String text) {
        Map<String, Object> entities = new java.util.LinkedHashMap<>();
        if (text == null) {
            return entities;
        }
        Matcher fm = FLEET.matcher(text);
        if (fm.find()) {
            entities.put("fleet", fm.group());
        }
        List<String> vins = new ArrayList<>();
        Matcher vm = VIN.matcher(text);
        while (vm.find() && vins.size() < 10) {
            vins.add(vm.group());
        }
        if (!vins.isEmpty()) {
            entities.put("vins", vins);
        }
        return entities;
    }

    /** 任务摘要：问题 + 结论截片（不存完整上下文） */
    private String buildSummary(String question, String conclusion) {
        String q = question == null ? "" : question;
        String c = conclusion == null ? "" : conclusion;
        if (c.length() > 200) {
            c = c.substring(0, 200);
        }
        return "Q: " + q + " | A: " + c;
    }
}
