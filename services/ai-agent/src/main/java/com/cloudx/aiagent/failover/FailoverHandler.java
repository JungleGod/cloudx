package com.cloudx.aiagent.failover;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 简易熔断器 — 连续失败 N 次自动打开，半开状态试探成功后关闭
 *
 * 状态机：CLOSED → OPEN（连续失败达阈值）→ HALF_OPEN（等待后试探）→ CLOSED / OPEN
 */
public class FailoverHandler {

    private final int maxFailures;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile long openedAt = 0;

    /** 半开状态等待时间（毫秒） */
    private static final long HALF_OPEN_DELAY_MS = 30_000;

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    public FailoverHandler(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public boolean isOpen() {
        State current = state.get();
        if (current == State.CLOSED) {
            return false;
        }
        if (current == State.OPEN) {
            // 超过等待时间，进入半开状态
            if (System.currentTimeMillis() - openedAt > HALF_OPEN_DELAY_MS) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                return false; // 半开状态下允许试探
            }
            return true; // 仍然 OPEN，拒绝请求
        }
        // HALF_OPEN 状态允许通过
        return false;
    }

    /** 记录成功，重置熔断器 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    /** 记录失败，返回 true 表示熔断器刚打开 */
    public boolean recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= maxFailures) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)
                    || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public State getState() {
        return state.get();
    }
}
