package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.GitRepository;
import org.apache.ibatis.annotations.Mapper;

/** {@link GitRepository} 的 MyBatis-Plus Mapper. */
@Mapper
public interface GitRepositoryMapper extends BaseMapper<GitRepository> {
}
