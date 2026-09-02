package com.dst.v2xagent.orchestration;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 编排模板（设计文档 §5.9）：多 capability 声明式编排，
 * 模型只能选择模板 id，绝不交给模型自由多跳。
 */
@Data
public class OrchestrationTemplate {

    private String id;
    private String display;

    /** 输入槽位（与伪 capability 的 params 对齐） */
    private Map<String, String> input = Map.of();

    private List<Step> steps = List.of();

    private Output output = new Output();

    private Budget budget = new Budget();

    @Data
    public static class Step {
        private String id;
        private String capability;
        /** 参数引用：${input.xxx} / ${steps.某步.id.字段}，可空（走默认） */
        private Map<String, String> params = Map.of();
        private int parallelGroup = 1;
        /** 失败是否阻断整个模板；false 则降级并在结论中声明 */
        private boolean required = true;
    }

    @Data
    public static class Output {
        /** 主表（TOOL_CALL_RESULT）步骤 id */
        private String primaryTable;
        /** 出图（CHART_SPEC）步骤 id */
        private String chart;
    }

    @Data
    public static class Budget {
        /** 单模板 capability 上限（设计约束：≤ 4） */
        private int maxCapabilities = 4;
        private long totalTimeoutMs = 20000;
    }
}
