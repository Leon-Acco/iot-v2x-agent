package com.dst.v2xagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 实体定义：Vehicle / Fleet / Region / Alarm / Fault / Trip / ChargingStation 统一语义 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EntityDefinition {
    /** 实体类型（大写） */
    private String type;
    /** 中文名 */
    private String name;
    /** 主键字段说明 */
    private String idField;
    /** 描述 */
    private String description;
}
