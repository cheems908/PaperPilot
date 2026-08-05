package com.paperpilot.api.mq;

/**
 * 任务创建领域事件：在 {@code createTask} 事务内发布，
 * 由 {@link TaskCreatedEventListener} 在 AFTER_COMMIT 阶段派发首阶段消息.
 *
 * <p>幂等命中（request_key 已存在）时不发布，避免重复派发。
 */
public record TaskCreatedEvent(Long taskId, String requestKey) {
}
