package com.dst.v2xagent.semantic;

import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.semantic.model.DimensionDefinition;
import com.dst.v2xagent.semantic.model.EntityDefinition;
import com.dst.v2xagent.semantic.model.MetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义注册中心：加载 semantic/*.yaml（Entity / Metric / Dimension）并校验
 * 校验规则（fail-fast）：指标绑定的 capability 必须存在、取值列与维度列必须在 capability 返回列中。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SemanticRegistry {

    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final Map<String, MetricDefinition> metrics = new ConcurrentHashMap<>();
    private final Map<String, EntityDefinition> entities = new ConcurrentHashMap<>();
    private final Map<String, DimensionDefinition> dimensions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        load(resolver.getResources("classpath:semantic/metrics.yaml"), MetricDefinition.class,
                MetricDefinition::getId, metrics);
        load(resolver.getResources("classpath:semantic/entities.yaml"), EntityDefinition.class,
                EntityDefinition::getType, entities);
        load(resolver.getResources("classpath:semantic/dimensions.yaml"), DimensionDefinition.class,
                DimensionDefinition::getId, dimensions);
        validate();
        log.info("语义层加载完成：{} 指标 / {} 实体 / {} 维度",
                metrics.size(), entities.size(), dimensions.size());
    }

    private <T> void load(Resource[] resources, Class<T> type,
                          java.util.function.Function<T, String> keyFn, Map<String, T> sink) throws Exception {
        for (Resource res : resources) {
            List<T> items = yamlMapper.readValue(res.getInputStream(),
                    yamlMapper.getTypeFactory().constructCollectionType(List.class, type));
            for (T item : items) {
                sink.put(keyFn.apply(item), item);
            }
        }
    }

    /** 跨层校验：指标 → capability 契约一致性（宁可启动失败，不可带病上线） */
    private void validate() {
        for (MetricDefinition m : metrics.values()) {
            CapabilityDefinition cap = capabilityRegistry.get(m.getCapability())
                    .orElseThrow(() -> new IllegalStateException(
                            "指标 " + m.getId() + " 绑定的 capability 不存在: " + m.getCapability()));
            List<String> columns = cap.getReturns().getColumns().stream()
                    .map(CapabilityDefinition.ColumnDef::getName).toList();
            if (m.getFormula() != null) {
                if (!columns.contains(m.getFormula().getNumerator())
                        || !columns.contains(m.getFormula().getDenominator())) {
                    throw new IllegalStateException("指标 " + m.getId() + " 公式列不在 capability 返回列中");
                }
            } else if (m.getValueColumn() != null && !columns.contains(m.getValueColumn())) {
                throw new IllegalStateException(
                        "指标 " + m.getId() + " 取值列 " + m.getValueColumn() + " 不在 capability 返回列中");
            }
            for (String dim : m.getDimensions()) {
                if (!dimensions.containsKey(dim)) {
                    throw new IllegalStateException("指标 " + m.getId() + " 引用未注册维度: " + dim);
                }
                String col = m.getDimensionColumns().get(dim);
                if (col != null && !columns.contains(col)) {
                    throw new IllegalStateException(
                            "指标 " + m.getId() + " 维度列 " + col + " 不在 capability 返回列中");
                }
            }
        }
    }

    public Optional<MetricDefinition> metric(String id) {
        return Optional.ofNullable(metrics.get(id));
    }

    /** 必需获取指标，不存在抛 PARAM_INVALID */
    public MetricDefinition requireMetric(String id) {
        return metric(id).orElseThrow(() -> ApiException.paramInvalid("未注册的指标: " + id));
    }

    public List<MetricDefinition> listMetrics() {
        return List.copyOf(metrics.values());
    }

    public Map<String, EntityDefinition> entities() {
        return Map.copyOf(entities);
    }

    public Map<String, DimensionDefinition> dimensions() {
        return Map.copyOf(dimensions);
    }
}
