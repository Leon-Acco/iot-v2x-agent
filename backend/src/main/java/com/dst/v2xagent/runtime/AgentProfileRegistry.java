package com.dst.v2xagent.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Profile：两个 Agent 的差异只在配置，同一 Harness 承载
 * 新增 Agent = 新增一份 yaml，不新增服务、不复制能力目录。
 */
@Slf4j
@Component
public class AgentProfileRegistry {

    private final Map<String, AgentProfile> profiles = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @PostConstruct
    public void init() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource res : resolver.getResources("classpath:agent-profiles/*.yaml")) {
            AgentProfile profile = yamlMapper.readValue(res.getInputStream(), AgentProfile.class);
            profiles.put(profile.getId(), profile);
            log.info("Agent Profile 加载: {} ({})", profile.getId(), profile.getDisplay());
        }
    }

    public AgentProfile get(String id) {
        return profiles.getOrDefault(id, profiles.get("data_base"));
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentProfile {
        private String id;
        private String display;
        /** 可见的 capability 域（catalog 裁剪粒度） */
        private List<String> capabilityDomains = List.of();
        /** 是否可读组织记忆（P1） */
        private boolean readOrgMemory = false;
        /** 空状态示例问题（前端引导） */
        private List<String> exampleQuestions = List.of();
    }
}
