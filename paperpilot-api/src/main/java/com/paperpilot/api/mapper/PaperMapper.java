package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.Paper;
import org.apache.ibatis.annotations.Mapper;

/** {@link Paper} 的 MyBatis-Plus Mapper. */
@Mapper
public interface PaperMapper extends BaseMapper<Paper> {
}
