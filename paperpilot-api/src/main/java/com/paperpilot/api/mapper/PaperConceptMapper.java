package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.PaperConcept;
import org.apache.ibatis.annotations.Mapper;

/** {@link PaperConcept} 的 MyBatis-Plus Mapper. */
@Mapper
public interface PaperConceptMapper extends BaseMapper<PaperConcept> {
}
