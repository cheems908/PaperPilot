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
    @Insert("INSERT INTO concept_code_mapping (concept_id, code_symbol_id, confidence, notes, semantic_score, "
            + "symbol_score, keyword_score, documentation_score, verification_score, total_score, mapping_status, "
            + "degraded, verification_reason, code_evidence, matched_tokens_json) VALUES (#{m.conceptId}, "
            + "#{m.codeSymbolId}, #{m.confidence}, #{m.notes}, #{m.semanticScore}, #{m.symbolScore}, "
            + "#{m.keywordScore}, #{m.documentationScore}, #{m.verificationScore}, #{m.totalScore}, "
            + "#{m.mappingStatus}, #{m.degraded}, #{m.verificationReason}, #{m.codeEvidence}, #{m.matchedTokensJson}) "
            + "ON DUPLICATE KEY UPDATE confidence = VALUES(confidence), notes = VALUES(notes), "
            + "semantic_score = VALUES(semantic_score), symbol_score = VALUES(symbol_score), "
            + "keyword_score = VALUES(keyword_score), documentation_score = VALUES(documentation_score), "
            + "verification_score = VALUES(verification_score), total_score = VALUES(total_score), "
            + "mapping_status = VALUES(mapping_status), degraded = VALUES(degraded), "
            + "verification_reason = VALUES(verification_reason), code_evidence = VALUES(code_evidence), "
            + "matched_tokens_json = VALUES(matched_tokens_json)")
    int upsert(@Param("m") ConceptCodeMapping mapping);
}
