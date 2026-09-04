package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * capability 注册中心
 * 启动时把 resources/capability/*.yaml 同步进 MySQL（已存在则跳过），
 * 运行期以 MySQL 为准（管理后台 CRUD 生效），内存索引加速选择器召回。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CapabilityRegistry {

    private final JdbcTemplate controlJdbcTemplate;
    // 元数据统一 snake_case 命名（与 yaml 文件、MySQL JSON 列一致）
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

    /** id → 定义（内存索引，发布后刷新） */
    private final Map<String, CapabilityDefinition> index = new ConcurrentHashMap<>();

    /** 启动加载：yaml 种子 → MySQL → 内存索引（每个定义都过静态校验） */
    @PostConstruct
    public void init() throws Exception {
        seedFromYaml();
        reload();
    }

    /** 把 resources/capability/*.yaml 种子同步进控制库（幂等） */
    private void seedFromYaml() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath:capability/*.yaml");
        } catch (java.io.FileNotFoundException e) {
            // capability dir no longer shipped (DB-native since V19); skip yaml seeding
            return;
        }
        for (Resource res : resources) {
            CapabilityDefinition def = yamlMapper.readValue(res.getInputStream(), CapabilityDefinition.class);
            CapabilityValidator.validate(def);
            Integer count = controlJdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM capability_definition WHERE tenant_id = 'T1' AND id = ?",
                    Integer.class, def.getId());
            if (count != null && count > 0) continue;
            insert(def);
            log.info("capability 种子入库: {}", def.getId());
        }
    }

    /** 从 MySQL 全量加载到内存索引 */
    public synchronized void reload() {
        List<CapabilityDefinition> all = controlJdbcTemplate.query(
                "SELECT * FROM capability_definition WHERE tenant_id = 'T1'",
                (rs, i) -> rowToDefinition(rs));
        index.clear();
        for (CapabilityDefinition def : all) {
            CapabilityValidator.validate(def);
            index.put(def.getId(), def);
        }
        log.info("capability 注册中心加载完成: {} 个", index.size());
    }

    public Optional<CapabilityDefinition> get(String id) {
        return Optional.ofNullable(index.get(id));
    }

    /** 必需获取，不存在抛 CAPABILITY_NOT_FOUND */
    public CapabilityDefinition require(String id) {
        return get(id).orElseThrow(() -> ApiException.capabilityNotFound("capability 不存在或未上线: " + id));
    }

    /** 全部 online 能力（选择器候选池） */
    public List<CapabilityDefinition> listOnline() {
        return index.values().stream().filter(d -> "online".equals(d.getStatus())).toList();
    }

    /** 全部能力（管理后台） */
    public List<CapabilityDefinition> listAll() {
        return List.copyOf(index.values());
    }

    /** 新增（草稿） */
    public void create(CapabilityDefinition def) {
        CapabilityValidator.validate(def);
        insert(def);
        reload();
    }

    /** 更新（元数据变更自动递增版本，缓存 key 含版本自动失效；旧版本快照入历史表，禁止覆盖式修改） */
    public void update(CapabilityDefinition def) {
        CapabilityValidator.validate(def);
        snapshotHistory(def.getId());
        def.setVersion(def.getVersion() + 1);
        controlJdbcTemplate.update("""
                UPDATE capability_definition SET display=?, description=?, aliases=?, domain=?, readonly=?,
                    params=?, returns_meta=?, chart_hint=?, source_tables=?, freshness_policy=?, limits=?, cache=?,
                    scopes=?, row_filter_policy=?, sample_questions=?, sql_template=?, status=?, version=?, owner=?
                WHERE tenant_id='T1' AND id=?
                """,
                def.getDisplay(), def.getDescription(), toJson(def.getAliases()), def.getDomain(), def.isReadonly(),
                toJson(def.getParams()), toJson(def.getReturns()), def.getChartHint(), toJson(def.getSourceTables()),
                toJson(def.getFreshnessPolicy()), toJson(def.getLimits()), toJson(def.getCache()),
                toJson(def.getScopes()), def.getRowFilterPolicy(), toJson(def.getSampleQuestions()),
                def.getSqlTemplate(), def.getStatus(), def.getVersion(), def.getOwner(), def.getId());
        reload();
    }

    /** 上下线 */
    public void changeStatus(String id, String status) {
        controlJdbcTemplate.update(
                "UPDATE capability_definition SET status=?, version=version+1 WHERE tenant_id='T1' AND id=?",
                status, id);
        reload();
    }

    private void insert(CapabilityDefinition def) {
        controlJdbcTemplate.update("""
                INSERT INTO capability_definition
                (id, kind, tenant_id, display, description, aliases, domain, readonly, params, returns_meta, chart_hint,
                 source_tables, freshness_policy, limits, cache, scopes, row_filter_policy, sample_questions,
                 sql_template, status, version, owner)
                VALUES (?, ?, 'T1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                def.getId(), def.getKind(), def.getDisplay(), def.getDescription(), toJson(def.getAliases()), def.getDomain(),
                def.isReadonly(), toJson(def.getParams()), toJson(def.getReturns()), def.getChartHint(),
                toJson(def.getSourceTables()), toJson(def.getFreshnessPolicy()), toJson(def.getLimits()),
                toJson(def.getCache()), toJson(def.getScopes()), def.getRowFilterPolicy(),
                toJson(def.getSampleQuestions()), def.getSqlTemplate(), def.getStatus(), def.getVersion(), def.getOwner());
    }

    /** 旧版本快照入历史表（更新 / 上下线前调用，保证旧 Agent 行为可追溯） */
    private void snapshotHistory(String id) {
        CapabilityDefinition old = index.get(id);
        if (old == null) {
            return;
        }
        try {
            controlJdbcTemplate.update(
                    "INSERT INTO capability_definition_history (capability_id, version, snapshot, status) VALUES (?,?,?,?)",
                    id, old.getVersion(), jsonMapper.writeValueAsString(old), old.getStatus());
        } catch (Exception e) {
            log.warn("capability 历史快照入库失败: {}", e.getMessage());
        }
    }

    /** 版本历史列表（新到旧） */
    public java.util.List<java.util.Map<String, Object>> listVersions(String id) {
        return controlJdbcTemplate.queryForList(
                "SELECT capability_id, version, status, updated_at FROM capability_definition_history"
                + " WHERE tenant_id = 'T1' AND capability_id = ? ORDER BY version DESC",
                id);
    }

    /** 读取指定历史版本定义（灰度回滚 / 审计用） */
    public java.util.Optional<CapabilityDefinition> getVersion(String id, int version) {
        java.util.List<String> rows = controlJdbcTemplate.queryForList(
                "SELECT snapshot FROM capability_definition_history"
                + " WHERE tenant_id = 'T1' AND capability_id = ? AND version = ?",
                String.class, id, version);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(jsonMapper.readValue(rows.get(0), CapabilityDefinition.class));
        } catch (Exception e) {
            throw new IllegalStateException("capability 历史版本解析失败: " + id + "@v" + version, e);
        }
    }

    private CapabilityDefinition rowToDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            CapabilityDefinition def = new CapabilityDefinition();
            def.setId(rs.getString("id"));
            def.setKind(rs.getString("kind"));
            def.setDisplay(rs.getString("display"));
            def.setDescription(rs.getString("description"));
            def.setAliases(readJson(rs.getString("aliases"), jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            def.setDomain(rs.getString("domain"));
            def.setReadonly(rs.getBoolean("readonly"));
            def.setParams(readJson(rs.getString("params"), jsonMapper.getTypeFactory().constructCollectionType(List.class, CapabilityDefinition.ParamDef.class)));
            def.setReturns(jsonMapper.readValue(rs.getString("returns_meta"), CapabilityDefinition.ReturnsDef.class));
            def.setChartHint(rs.getString("chart_hint"));
            def.setSourceTables(readJson(rs.getString("source_tables"), jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            String fp = rs.getString("freshness_policy");
            if (fp != null) def.setFreshnessPolicy(jsonMapper.readValue(fp, CapabilityDefinition.FreshnessPolicy.class));
            def.setLimits(jsonMapper.readValue(rs.getString("limits"), CapabilityDefinition.Limits.class));
            String cache = rs.getString("cache");
            if (cache != null) def.setCache(jsonMapper.readValue(cache, CapabilityDefinition.CachePolicy.class));
            def.setScopes(readJson(rs.getString("scopes"), jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            def.setRowFilterPolicy(rs.getString("row_filter_policy"));
            def.setSampleQuestions(readJson(rs.getString("sample_questions"), jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            def.setSqlTemplate(rs.getString("sql_template"));
            def.setStatus(rs.getString("status"));
            def.setVersion(rs.getInt("version"));
            def.setOwner(rs.getString("owner"));
            // 分区键不进表字段，从 yaml 种子带不过来——P0 约定从 params 中的 daterange 推导校验，此处留空
            return def;
        } catch (java.sql.SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("capability 行解析失败: " + rs.getString("id"), e);
        }
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : jsonMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T readJson(String json, com.fasterxml.jackson.databind.JavaType type) {
        try {
            return json == null ? null : jsonMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
