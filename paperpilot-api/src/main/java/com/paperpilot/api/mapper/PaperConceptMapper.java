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
     * 幂等 upsert：稳定键 (paper_id, concept_key)。重复执行刷新术语与全部证据。
     */
    @Insert("INSERT INTO paper_concept (paper_id, concept_key, concept_name, aliases_json, mentions_json, "
            + "extractor_version, decision, abstention_reason, evidence_text, evidence_location) "
            + "VALUES (#{p.paperId}, #{p.conceptKey}, #{p.conceptName}, #{p.aliasesJson}, #{p.mentionsJson}, "
            + "#{p.extractorVersion}, #{p.decision}, #{p.abstentionReason}, #{p.evidenceText}, #{p.evidenceLocation}) "
            + "ON DUPLICATE KEY UPDATE concept_name = VALUES(concept_name), aliases_json = VALUES(aliases_json), "
            + "mentions_json = VALUES(mentions_json), extractor_version = VALUES(extractor_version), "
            + "decision = VALUES(decision), abstention_reason = VALUES(abstention_reason), "
            + "evidence_text = VALUES(evidence_text), evidence_location = VALUES(evidence_location)")
    int upsert(@Param("p") PaperConcept concept);
}
