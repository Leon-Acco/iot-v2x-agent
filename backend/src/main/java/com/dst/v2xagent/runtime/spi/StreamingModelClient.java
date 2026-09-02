package com.dst.v2xagent.runtime.spi;

/**
 * 流式模型调用 SPI（框架隔离带）
 * 用于结论生成：token 流式回吐。
 */
public interface StreamingModelClient {

    /** 流式请求 */
    record StreamingRequest(
            String systemPrompt,
            String userPrompt,
            double temperature,
            int timeoutMs
    ) {}

    /** token 接收器 */
    @FunctionalInterface
    interface TokenSink {
        void onToken(String token);
    }

    /** 流式生成，逐 token 回调 */
    void stream(StreamingRequest request, TokenSink sink);
}
