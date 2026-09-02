package com.dst.v2xagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排模板注册中心：加载 resources/orchestration/*.yaml 并做静态校验。
 * 约束：单模板 capability ≤ 4、并行批 ≤ 2、引用的步骤 id 唯一。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationRegistry {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private final Map<String, OrchestrationTemplate> index = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:orchestration/*.yaml");
        for (Resource res : resources) {
            OrchestrationTemplate template = yamlMapper.readValue(res.getInputStream(), OrchestrationTemplate.class);
            validate(template);
            index.put(template.getId(), template);
            log.info("编排模板加载: {} ({} 个步骤)", template.getId(), template.getSteps().size());
        }
    }

    public Optional<OrchestrationTemplate> get(String id) {
        return Optional.ofNullable(index.get(id));
    }

    public OrchestrationTemplate require(String id) {
        return get(id).orElseThrow(() -> new IllegalStateException("编排模板不存在: " + id));
    }

    /** 静态校验：约束超限直接启动失败（宁可 fail-fast） */
    private void validate(OrchestrationTemplate template) {
        if (template.getId() == null || template.getId().isBlank()) {
            throw new IllegalStateException("编排模板缺少 id");
        }
        if (template.getSteps().isEmpty()) {
            throw new IllegalStateException("编排模板 " + template.getId() + " 没有步骤");
        }
        if (template.getSteps().size() > template.getBudget().getMaxCapabilities()) {
            throw new IllegalStateException("编排模板 " + template.getId()
                    + " 步骤数超过预算 " + template.getBudget().getMaxCapabilities());
        }
        long distinctGroups = template.getSteps().stream().map(OrchestrationTemplate.Step::getParallelGroup).distinct().count();
        if (distinctGroups > 2) {
            throw new IllegalStateException("编排模板 " + template.getId() + " 并行批次超过 2（延迟不可预测）");
        }
        long distinctIds = template.getSteps().stream().map(OrchestrationTemplate.Step::getId).distinct().count();
        if (distinctIds != template.getSteps().size()) {
            throw new IllegalStateException("编排模板 " + template.getId() + " 步骤 id 重复");
        }
        if (template.getOutput().getPrimaryTable() == null) {
            throw new IllegalStateException("编排模板 " + template.getId() + " 缺少 output.primary_table");
        }
    }
}
