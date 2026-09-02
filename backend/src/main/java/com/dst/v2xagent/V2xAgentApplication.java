package com.dst.v2xagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 车联网平台 Agent 后端启动类（P0 打样）
 * 单制品承载 AG-UI 接入、Agent 运行层、能力层与管理后台
 */
@SpringBootApplication
public class V2xAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(V2xAgentApplication.class, args);
    }
}
