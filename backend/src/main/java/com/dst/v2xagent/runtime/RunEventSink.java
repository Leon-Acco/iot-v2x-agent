package com.dst.v2xagent.runtime;

import com.dst.v2xagent.agui.AgUiEvent;

import java.util.Map;

/** 运行事件出口 SPI：AG-UI 适配层实现（设计文档 §18 框架隔离带）。 */
public interface RunEventSink {

    /** 发送一个运行事件 */
    void emit(AgUiEvent event);

    /** 发送结论文本增量（TEXT_MESSAGE_CONTENT） */
    void emitText(String delta);

    /** 是否已关闭 */
    boolean isClosed();

    /** 关闭出口并结束事件流 */
    void close();

    /** 运行期产物快照（表格/图表/可视化，落 payload_json 供会话恢复）；适配层不支持时返回 null */
    default Map<String, Object> snapshot() {
        return null;
    }
}
