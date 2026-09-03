package com.dst.v2xagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 维度定义：fleet / vehicle / province / city / district / alarm_type / fault_type / day / hour */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DimensionDefinition {
    /** 维度 id */
    private String id;
    /** 中文名 */
    private String name;
    /** 类型：category / time / geo */
    private String type = "category";
    /** 层级（geo 维度用：province -> city -> district） */
    private String parent;
}
