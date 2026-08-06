package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** {@link ConceptCodeMapping} 的 MyBatis-Plus Mapper. */
@Mapper
public interface ConceptCodeMappingMapper extends BaseMapper<ConceptCodeMapping> {

    /**
     * 幂等 upsert：稳定键 (concept_id, code_symbol_id)。重复执行刷新分数/说明，不产生重复映射。
     */
    @Insert("INSERT INTO concept_code_mapping (concept_id, code_symbol_id, confidence, notes) "
            + "VALUES (#{m.conceptId}, #{m.codeSymbolId}, #{m.confidence}, #{m.notes}) "
            + "ON DUPLICATE KEY UPDATE confidence = VALUES(confidence), notes = VALUES(notes)")
    int upsert(@Param("m") ConceptCodeMapping mapping);
}
