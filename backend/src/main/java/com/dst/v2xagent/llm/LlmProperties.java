package com.dst.v2xagent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 多供应商配置（OpenAI 兼容端点，配置切换：deepseek / glm / kimi / mock）
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 当前启用供应商 */
    private String active = "mock";

    private Map<String, Provider> providers = new HashMap<>();

    private Stage extract = new Stage(2500, 1, 0.1);
    private Stage conclude = new Stage(8000, 0, 0.3);

    @Data
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String extractModel;
        private String concludeModel;
    }

    @Data
    public static class Stage {
        private int timeoutMs;
        private int maxRetries;
        private double temperature;

        public Stage() {}

        public Stage(int timeoutMs, int maxRetries, double temperature) {
            this.timeoutMs = timeoutMs;
            this.maxRetries = maxRetries;
            this.temperature = temperature;
        }
    }

    /** 当前供应商配置 */
    public Provider activeProvider() {
        Provider p = providers.get(active);
        if (p == null) throw new IllegalStateException("LLM 供应商未配置: " + active);
        return p;
    }
}
