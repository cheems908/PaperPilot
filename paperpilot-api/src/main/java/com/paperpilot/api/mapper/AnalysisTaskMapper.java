package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@link AnalysisTask} 的 MyBatis-Plus Mapper. */
@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    /** 锁定任务行，使取消与结果提交按 MySQL 行锁顺序决定唯一终态。 */
    @Select("SELECT * FROM analysis_task WHERE id = #{id} FOR UPDATE")
    AnalysisTask selectByIdForUpdate(@Param("id") Long id);
}
