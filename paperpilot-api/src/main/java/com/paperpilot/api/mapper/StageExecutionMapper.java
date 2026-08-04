package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.StageExecution;
import org.apache.ibatis.annotations.Mapper;

/** {@link StageExecution} 的 MyBatis-Plus Mapper. */
@Mapper
public interface StageExecutionMapper extends BaseMapper<StageExecution> {
}
