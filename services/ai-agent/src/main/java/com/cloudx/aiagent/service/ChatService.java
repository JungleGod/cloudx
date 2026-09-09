package com.cloudx.aiagent.service;

import com.cloudx.aiagent.provider.StreamCallback;
import com.cloudx.aiagent.routing.ModelRouter;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ModelRouter modelRouter;
    private final CallLogClient callLogClient;

    public RouteResult chat(String message) {
        return chat(message, null, null, null, null);
    }

    public RouteResult chat(String message, String taskType, Long userId) {
        return chat(message, null, null, taskType, userId);
    }

    /**
     * 多轮对话：将历史消息拼入 prompt，让模型感知上下文
     */
    public RouteResult chat(String message, java.util.List<java.util.Map<String, String>> history,
                            String model, String taskType, Long userId) {
        String fullPrompt = buildPrompt(message, history, model);
        long start = System.currentTimeMillis();
        RouteResult result = modelRouter.route(fullPrompt, model, taskType);
        long latency = System.currentTimeMillis() - start;

        // 估算 token 数
        int tokensInput = fullPrompt.length() / 2;
        int tokensOutput = result.reply().length() / 2;

        callLogClient.record(userId, result.model(), fullPrompt, result.reply(),
                tokensInput, tokensOutput, latency, true, null);

        return result;
    }

    /**
     * 将历史消息格式化为对话上下文字符串
     * 当检测到切换模型时，显式告知新模型不要沿用之前 AI 的身份
     */
    private String buildPrompt(String message, java.util.List<java.util.Map<String, String>> history, String currentModel) {
        if (history == null || history.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是历史对话：\n");

        String lastModel = null;
        for (java.util.Map<String, String> msg : history) {
            if ("user".equals(msg.get("role"))) {
                sb.append("用户: ").append(msg.get("content")).append("\n");
            } else {
                String m = msg.get("model");
                String label = (m != null && !m.isBlank()) ? "AI(" + m + ")" : "AI";
                sb.append(label).append(": ").append(msg.get("content")).append("\n");
                lastModel = m;
            }
        }

        // 检测模型切换：如果当前模型与上一条 AI 消息的模型不同，插入身份提示
        if (currentModel != null && !currentModel.isBlank()
                && lastModel != null && !lastModel.isBlank()
                && !lastModel.equals(currentModel)) {
            sb.append("\n");
            sb.append("[注意：你是一个新的 AI 模型，你的真实身份是 ").append(currentModel);
            sb.append("。对话历史中标记为 AI(").append(lastModel);
            sb.append(") 的回复不是你生成的，是另一个 AI 模型生成的。");
            sb.append("请以你自己的真实身份（").append(currentModel).append("）回答用户当前的问题，不要冒充历史中的 AI。]\n");
        }

        sb.append("\n请根据以上对话上下文，回答用户的当前问题：\n");
        sb.append(message);
        return sb.toString();
    }

    /**
     * 流式对话 — 每收到一个 token 回调一次
     */
    public void chatStream(String message, java.util.List<java.util.Map<String, String>> history,
                           String model, String taskType, Long userId, StreamCallback callback) {
        String fullPrompt = buildPrompt(message, history, model);
        long start = System.currentTimeMillis();
        String[] replyHolder = new String[1]; // 用于在回调间传递完整回复
        replyHolder[0] = "";

        // 包装回调：累加 token + 完成后记录调用日志
        StreamCallback wrapped = new StreamCallback() {
            @Override
            public void onToken(String token) {
                replyHolder[0] += token;
                callback.onToken(token);
            }

            @Override
            public void onComplete() {
                long latency = System.currentTimeMillis() - start;
                String reply = replyHolder[0];
                String usedModel = (model != null && !model.isBlank()) ? model : "auto";
                int tokensInput = fullPrompt.length() / 2;
                int tokensOutput = reply.length() / 2;
                callLogClient.record(userId, usedModel, fullPrompt, reply,
                        tokensInput, tokensOutput, latency, true, null);
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                long latency = System.currentTimeMillis() - start;
                String reply = replyHolder[0];
                String usedModel = (model != null && !model.isBlank()) ? model : "auto";
                int tokensInput = fullPrompt.length() / 2;
                int tokensOutput = reply.length() / 2;
                callLogClient.record(userId, usedModel, fullPrompt, reply,
                        tokensInput, tokensOutput, latency, false,
                        error != null ? error.getMessage() : "unknown");
                callback.onError(error);
            }
        };

        modelRouter.routeStream(fullPrompt, model, taskType, wrapped);
    }

    /** 多模态对话 — 图片 + 文字 */
    public RouteResult chatMultimodal(String text, List<String> base64Images,
                                      String model, String taskType, Long userId) {
        RouteResult result = modelRouter.routeMultimodal(text, base64Images, model, taskType);
        // 异步记录调用日志
        callLogClient.record(userId, result.model(), text, result.reply(),
                text.length() / 2, result.reply().length() / 2,
                0, true, null);
        return result;
    }

    public List<Map<String, Object>> modelStatus() {
        return modelRouter.getProviderStatus();
    }
}
