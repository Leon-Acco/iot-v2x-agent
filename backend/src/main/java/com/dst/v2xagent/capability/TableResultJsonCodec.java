package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TableResult 的 JSON 编解码（Redis L2 缓存用）
 * Object[] 行统一序列化为 JSON 数组；日期时间类型转字符串。
 */
public final class TableResultJsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TableResultJsonCodec() {}

    public static String encode(TableResult result) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("columns", result.columns());
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : result.rows()) {
            List<Object> r = new ArrayList<>();
            for (Object cell : row) r.add(cell == null ? null : cell.toString());
            rows.add(r);
        }
        map.put("rows", rows);
        map.put("rowCount", result.rowCount());
        map.put("truncated", result.truncated());
        map.put("freshness", result.freshness());
        map.put("stats", result.stats());
        return MAPPER.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    public static TableResult decode(String json) throws Exception {
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        List<CapabilityDefinition.ColumnDef> columns = MAPPER.convertValue(map.get("columns"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, CapabilityDefinition.ColumnDef.class));
        List<Object[]> rows = new ArrayList<>();
        for (List<Object> r : (List<List<Object>>) map.get("rows")) {
            rows.add(r.toArray());
        }
        TableResult.Freshness freshness = MAPPER.convertValue(map.get("freshness"), TableResult.Freshness.class);
        TableResult.ExecStats stats = MAPPER.convertValue(map.get("stats"), TableResult.ExecStats.class);
        return new TableResult(columns, rows, (int) map.get("rowCount"),
                Boolean.TRUE.equals(map.get("truncated")), freshness, stats);
    }
}
