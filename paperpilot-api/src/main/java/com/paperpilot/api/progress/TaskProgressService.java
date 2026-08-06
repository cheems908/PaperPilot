package com.paperpilot.api.progress;

import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.enums.TaskStage;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.dto.progress.TaskProgressSnapshot;
import com.paperpilot.api.dto.progress.TaskProgressView;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 任务进度缓存服务（Redis 仅做短期 UI 快照，MySQL 才是正式状态真相来源）.
 *
 * <p>写路径：正式状态先提交 MySQL，再尽力更新 Redis；Redis 失败只告警，不影响任务完成。
 * 进度不倒退：比当前更低的 progress 被拒绝（旧/乱序事件不覆盖新快照）；
 * 重试用 {@link #reset} 清键后重新写入。
 * 查询：{@link #getView} 以 MySQL status 优先，Redis 只补充 progress/message；终态以 MySQL 为准。
 */
@Service
public class TaskProgressService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressService.class);

    /** 每阶段稳定进度区间（避免倒退）。 */
    private static final Map<TaskStage, int[]> STAGE_RANGE = Map.of(
            TaskStage.PARSE_PAPER, new int[]{0, 25},
            TaskStage.CLONE_REPOSITORY, new int[]{25, 50},
            TaskStage.INDEX_CODE, new int[]{50, 75},
            TaskStage.MAP_CONCEPTS, new int[]{75, 95});

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final TaskProgressProperties properties;
    private final AnalysisTaskMapper taskMapper;

    public TaskProgressService(ObjectProvider<StringRedisTemplate> redisProvider,
                               TaskProgressProperties properties, AnalysisTaskMapper taskMapper) {
        this.redisProvider = redisProvider;
        this.properties = properties;
        this.taskMapper = taskMapper;
    }

    /** 写进度快照（best-effort；进度不倒退；无 Redis 时静默跳过）。 */
    public void update(Long taskId, TaskStatus status, TaskStage stage, Integer progress, String message) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                log.debug("Redis 未配置，跳过进度写入 taskId={}", taskId);
                return;
            }
            TaskProgressSnapshot current = read(taskId);
            if (current != null && progress != null && current.progress() != null
                    && progress < current.progress()) {
                log.debug("进度不倒退，跳过 taskId={} progress {} -> {}", taskId, current.progress(), progress);
                return;
            }
            TaskProgressSnapshot snap = new TaskProgressSnapshot(
                    TaskProgressSnapshot.SCHEMA_VERSION, taskId,
                    status == null ? null : status.name(),
                    stage == null ? null : stage.name(),
                    progress, message, Instant.now());
            redis.opsForValue().set(properties.keyFor(taskId),
                    StageSnapshotContract.MAPPER.writeValueAsString(snap), properties.ttl());
        } catch (Exception e) {
            log.warn("Redis 进度写入失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 读取当前快照；无 Redis 或读取失败返回 null。 */
    public TaskProgressSnapshot read(Long taskId) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return null;
            }
            String json = redis.opsForValue().get(properties.keyFor(taskId));
            return json == null ? null : StageSnapshotContract.MAPPER.readValue(json, TaskProgressSnapshot.class);
        } catch (Exception e) {
            log.warn("Redis 进度读取失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /** 清空进度键（重试重置）。 */
    public void reset(Long taskId) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(properties.keyFor(taskId));
            }
        } catch (Exception e) {
            log.warn("Redis 进度清理失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 查询视图：MySQL status 优先，Redis 补充 progress/message；终态固定 100/终态消息。 */
    public TaskProgressView getView(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        TaskStatus status = task.getStatus();
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
            return new TaskProgressView(taskId, status.name(), null, 100, terminalMessage(status));
        }
        TaskProgressSnapshot snap = read(taskId);
        return new TaskProgressView(taskId, status.name(),
                snap == null ? null : snap.stage(),
                snap == null ? 0 : snap.progress(),
                snap == null ? null : snap.message());
    }

    /** 阶段进度区间起点。 */
    public int stageStart(TaskStage stage) {
        int[] range = STAGE_RANGE.get(stage);
        return range == null ? 0 : range[0];
    }

    /** 阶段进度区间终点。 */
    public int stageEnd(TaskStage stage) {
        int[] range = STAGE_RANGE.get(stage);
        return range == null ? 100 : range[1];
    }

    private static String terminalMessage(TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> "任务完成";
            case FAILED -> "任务失败";
            case CANCELLED -> "任务已取消";
            default -> "任务终态";
        };
    }
}
