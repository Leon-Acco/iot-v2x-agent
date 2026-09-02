package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.common.ApiException;
import java.util.concurrent.TimeoutException;

/** AgentScope 异常到项目错误码的映射（与前端 FailCard 契约保持一致）。 */
final class AgentScopeErrorMapper {

    private AgentScopeErrorMapper() {}

    /** 映射为 ApiException：超时 -> LLM_TIMEOUT，其余 -> LLM_ERROR（可重试） */
    static ApiException map(Throwable e, String stage, String timeoutMsg) {
        Throwable t = e;
        while (t instanceof RuntimeException && t.getCause() != null
                && !(t instanceof ApiException) && !(t instanceof TimeoutException)) {
            t = t.getCause();
        }
        if (t instanceof ApiException ae) {
            return ae;
        }
        if (t instanceof TimeoutException) {
            return new ApiException("LLM_TIMEOUT", stage, true, timeoutMsg);
        }
        String msg = String.valueOf(e.getMessage());
        if (msg.length() > 200) {
            msg = msg.substring(0, 200);
        }
        return new ApiException("LLM_ERROR", stage, true, "模型调用失败: " + msg);
    }
}
