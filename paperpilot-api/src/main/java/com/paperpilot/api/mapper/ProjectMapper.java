package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.Project;
import org.apache.ibatis.annotations.Mapper;

/** {@link Project} 的 MyBatis-Plus Mapper. */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
