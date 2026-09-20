package com.cloudx.aiagent.tool;

import com.cloudx.aiagent.agent.AgentExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具执行器 — 按 category 分发到不同 Handler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final BuiltInToolHandler builtInHandler;
    private final HttpToolHandler httpHandler;
    private final InternalApiToolHandler internalApiHandler;
    private final AgentDispatchHandler agentDispatchHandler;

    /** 执行工具调用（带完整定义和执行上下文） */
    public String execute(ToolDefinition tool, String argumentsJson, AgentExecutionContext ctx)
            throws ToolExecutionException {
        long start = System.currentTimeMillis();
        try {
            String result = switch (tool.getCategory()) {
                case "built-in"     -> builtInHandler.execute(tool.getBuiltinHandler(), argumentsJson,
                        ctx.getCallerRole(), ctx.getUserId());
                case "http"         -> httpHandler.execute(tool, argumentsJson);
                case "internal-api" -> internalApiHandler.execute(tool, argumentsJson,
                        ctx.getCallerRole(), ctx.getUserId());
                case "agent"        -> agentDispatchHandler.execute(argumentsJson, ctx);
                default -> throw new ToolExecutionException("Unknown tool category: " + tool.getCategory());
            };
            long elapsed = System.currentTimeMillis() - start;
            log.info("Tool [{}] executed in {}ms, result length={}", tool.getName(), elapsed,
                    result != null ? result.length() : 0);
            return result;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Tool [{}] failed after {}ms: {}", tool.getName(), elapsed, e.getMessage());
            throw new ToolExecutionException("Tool execution failed: " + e.getMessage(), e);
        }
    }

    public static class ToolExecutionException extends Exception {
        public ToolExecutionException(String message) {
            super(message);
        }
        public ToolExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}