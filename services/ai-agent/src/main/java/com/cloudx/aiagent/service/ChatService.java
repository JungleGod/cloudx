package com.cloudx.aiagent.service;

import com.cloudx.aiagent.routing.ModelRouter;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ModelRouter modelRouter;

    public RouteResult chat(String message) {
        return chat(message, null);
    }

    public RouteResult chat(String message, String taskType) {
        return modelRouter.route(message, taskType);
    }

    /** 获取所有模型的状态 */
    public Map<String, String> modelStatus() {
        return modelRouter.getProviderStatus();
    }
}
