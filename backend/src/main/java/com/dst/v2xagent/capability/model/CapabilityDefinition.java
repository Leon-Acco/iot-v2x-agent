package com.dst.v2xagent.capability.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * capability 元数据定义（本系统最核心的资产）
 * 单一事实源：模型 Schema、Java 校验器、管理后台表单均由其生成。
 * 对应 capability_definition 表与 resources/capability/*.yaml。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapabilityDefinition {

    /** 能力 id，不可变（审计与评测集引用） */
    private String id;

    /** 类型：capability=单能力（默认）；orchestration=编排模板引用（id 与 orchestration/*.yaml 模板同名） */
    private String kind = "capability";
    /** 中文显示名（给人看） */
    private String display;
    /** 能力描述（给模型看） */
    private String description;
    /** 别名：召回准确率主力 */
    private List<String> aliases = List.of();
    /** 域：online/location/mileage/alarm/fault/charge/trip/geofence/schema */
    private String domain;
    private boolean readonly = true;
    /** 参数定义 */
    private List<ParamDef> params = List.of();
    /** 返回结构 */
    private ReturnsDef returns;
    /** 图表类型强提示 */
    private String chartHint;
    /** 依赖的分析表（时效水位用） */
    private List<String> sourceTables = List.of();
    /** 时效策略 */
    private FreshnessPolicy freshnessPolicy;
    /** 执行限额 */
    private Limits limits = new Limits();
    /** 缓存策略 */
    private CachePolicy cache = new CachePolicy();
    /** 所需 scope */
    private List<String> scopes = List.of();
    /** 行级权限注入方式：by_fleet / by_vin / by_org（安全关键，缺失则注册失败） */
    private String rowFilterPolicy;
    /** 分区键列名（声明后 SQL 模板 WHERE 必须命中） */
    private String partitionColumn;
    /** 示例问题（召回语料 + 前端空状态） */
    private List<String> sampleQuestions = List.of();
    /** SQL 模板：必须含 ACL 占位符 */
    private String sqlTemplate;
    /** 状态：draft/staging/online/deprecated */
    private String status = "online";
    private int version = 1;
    private String owner;

    /** 参数定义 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParamDef {
        private String name;
        /** string / int / array&lt;string&gt; / daterange */
        private String type;
        private boolean required = false;
        private String description;
        /** 时间跨度上限（天） */
        private Integer maxSpanDays;
        /** 数组长度上限 */
        private Integer maxItems;
        private Object defaultValue;
        private List<String> enumValues;
    }

    /** 返回结构定义 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReturnsDef {
        /** table / metric / geo */
        private String shape = "table";
        private List<ColumnDef> columns = List.of();
    }

    /** 列定义：语义类型直接决定图表与表格渲染 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnDef {
        private String name;
        /** time / category / metric / geo_lng / geo_lat / id */
        private String semantic;
        private String display;
        private String unit;
        private Integer scale;
    }

    /** 时效策略 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FreshnessPolicy {
        /** realtime / t_plus_0 / t_plus_1 */
        private String type = "t_plus_0";
        private int expectedDelayMin = 5;
    }

    /** 执行限额 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Limits {
        private int maxRows = 1000;
        private int timeoutMs = 15000;
        private int maxSpanDays = 90;
    }

    /** 缓存策略 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CachePolicy {
        private boolean cacheable = true;
        private int ttlSeconds = 300;
    }
}
