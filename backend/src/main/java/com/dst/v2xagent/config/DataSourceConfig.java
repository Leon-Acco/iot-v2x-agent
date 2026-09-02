package com.dst.v2xagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源强隔离配置
 * control：MySQL 控制库（会话/capability 元数据/反馈/审计），允许业务事务
 * analytics：Doris 只读分析源（未配置 Doris 时降级为 MySQL 模拟数仓）
 * 两者使用不同配置前缀、连接池，注入时强制 @Qualifier
 */
@Configuration
public class DataSourceConfig {

    /** 控制库连接属性（前缀 datasource.control） */
    @Bean
    @Primary
    @ConfigurationProperties("datasource.control")
    public DataSourceProperties controlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 控制库数据源 */
    @Bean
    @Primary
    @ConfigurationProperties("datasource.control.hikari")
    public DataSource controlDataSource() {
        return controlDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /** 控制库 JdbcTemplate */
    @Bean
    @Primary
    public JdbcTemplate controlJdbcTemplate(DataSource controlDataSource) {
        return new JdbcTemplate(controlDataSource);
    }

    /** 分析库连接属性（前缀 datasource.analytics） */
    @Bean
    @ConfigurationProperties("datasource.analytics")
    public DataSourceProperties analyticsDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** 分析库数据源（Doris 只读账号 / MySQL 模拟数仓） */
    @Bean
    @ConfigurationProperties("datasource.analytics.hikari")
    public DataSource analyticsDataSource() {
        return analyticsDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /** 分析库 JdbcTemplate：只允许出现在能力执行器链路 */
    @Bean
    public JdbcTemplate analyticsJdbcTemplate(@Qualifier("analyticsDataSource") DataSource analyticsDataSource) {
        return new JdbcTemplate(analyticsDataSource);
    }
}
