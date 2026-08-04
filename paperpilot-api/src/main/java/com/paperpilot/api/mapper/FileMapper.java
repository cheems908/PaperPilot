package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.File;
import org.apache.ibatis.annotations.Mapper;

/** {@link File} 的 MyBatis-Plus Mapper. */
@Mapper
public interface FileMapper extends BaseMapper<File> {
}
