package com.paperpilot.api.mq;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 任务创建事务提交后监听器：事务提交才派发首阶段，回滚不触发（无幽灵消息）.
 */
@Component
@RequiredArgsConstructor
public class TaskCreatedEventListener {

    private final AnalysisTaskDispatcher analysisTaskDispatcher;

    /** AFTER_COMMIT 阶段读取已提交数据并派发首阶段消息。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        analysisTaskDispatcher.dispatchFirstStage(event.taskId());
    }
}
