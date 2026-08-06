package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.PaperConcept;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** {@link PaperConcept} 的 MyBatis-Plus Mapper. */
@Mapper
public interface PaperConceptMapper extends BaseMapper<PaperConcept> {

    /**
     * 幂等 upsert：稳定键 (paper_id, concept_name)。重复执行刷新证据，不产生重复概念。
     */
    @Insert("INSERT INTO paper_concept (paper_id, concept_name, evidence_text, evidence_location) "
            + "VALUES (#{p.paperId}, #{p.conceptName}, #{p.evidenceText}, #{p.evidenceLocation}) "
            + "ON DUPLICATE KEY UPDATE evidence_text = VALUES(evidence_text), "
            + "evidence_location = VALUES(evidence_location)")
    int upsert(@Param("p") PaperConcept concept);
}
