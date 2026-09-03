
@echo off
rem 车联网平台 Agent 一键启动（P0）
chcp 65001 > nul
cd /d %~dp0backend
mvn -s D:\tool\apache-maven-3.9.6\conf\settings-dst.xml spring-boot:run -Dspring-boot.run.jvmArguments="-Xms128m -Xmx512m"
