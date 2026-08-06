package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.dto.mapping.MappingCandidateDto;
import com.paperpilot.api.dto.mapping.MappingConceptDto;
import com.paperpilot.api.dto.mapping.MappingOutput;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAP_CONCEPTS 结果持久化：概念幂等写 paper_concept，映射按稳定键
 * {@code (concept_id, code_symbol_id)} 幂等 upsert 到 concept_code_mapping；
 * 代码坐标经 code_symbol 按 (repository_id, commit_sha, file_path, qualified_name) 解析。
 * 返回仅含摘要的快照 JSON。
 */
@Service
@RequiredArgsConstructor
public class MappingPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MappingPersistenceService.class);

    private final PaperConceptMapper paperConceptMapper;
    private final ConceptCodeMappingMapper conceptCodeMappingMapper;
    private final CodeSymbolMapper codeSymbolMapper;

    /** 幂等持久化并返回摘要快照 JSON。 */
    public String persist(Long paperId, Long repositoryId, WorkerStageResponse response) {
        if (paperId == null) {
            throw new RuntimeException("MAP_CONCEPTS 阶段缺少 paperId，无法持久化概念");
        }
        MappingOutput output = StageSnapshotContract.MAPPER.convertValue(response.output(), MappingOutput.class);
        int conceptCount = 0;
        int mappingCount = 0;
        for (MappingConceptDto concept : output.concepts() == null ? List.<MappingConceptDto>of() : output.concepts()) {
            Long conceptId = upsertConcept(paperId, concept);
            if (conceptId == null) {
                continue;
            }
            conceptCount++;
            for (MappingCandidateDto candidate : concept.candidates() == null
                    ? List.<MappingCandidateDto>of() : concept.candidates()) {
                CodeSymbol symbol = resolveSymbol(repositoryId, candidate.symbolRef());
                if (symbol == null) {
                    continue; // 代码坐标无法关联 code_symbol → 跳过
                }
                upsertMapping(conceptId, symbol.getId(), candidate);
                mappingCount++;
            }
        }
        int needsReview = 0;
        for (MappingConceptDto concept : output.concepts() == null ? List.<MappingConceptDto>of() : output.concepts()) {
            if (concept.candidates() == null) {
                continue;
            }
            needsReview += concept.candidates().stream()
                    .filter(c -> "NEEDS_REVIEW".equals(c.status())).count();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("commitSha", output.commitSha());
        summary.put("conceptCount", conceptCount);
        summary.put("mappingCount", mappingCount);
        summary.put("needsReviewCount", needsReview);
        log.info("concept_code_mapping persist paperId={} conceptCount={} mappingCount={}",
                paperId, conceptCount, mappingCount);
        try {
            return StageSnapshotContract.MAPPER.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("mapping summary serialization failed", e);
        }
    }

    private Long upsertConcept(Long paperId, MappingConceptDto concept) {
        if (concept.conceptId() == null || !concept.conceptId().matches("^pc_[0-9a-f]{24}$")) {
            throw new IllegalArgumentException("invalid production conceptId");
        }
        PaperConcept row = new PaperConcept();
        row.setPaperId(paperId);
        row.setConceptKey(concept.conceptId());
        row.setConceptName(concept.term());
        row.setAliasesJson(json(concept.aliases()));
        row.setMentionsJson(json(concept.mentions()));
        row.setExtractorVersion(concept.extractorVersion());
        row.setDecision(concept.decision());
        row.setAbstentionReason(concept.abstentionReason());
        row.setEvidenceText(concept.evidenceText());
        row.setEvidenceLocation(locationOf(concept));
        paperConceptMapper.upsert(row);
        PaperConcept saved = findConcept(paperId, concept.conceptId());
        return saved == null ? null : saved.getId();
    }

    private PaperConcept findConcept(Long paperId, String conceptKey) {
        return paperConceptMapper.selectOne(new LambdaQueryWrapper<PaperConcept>()
                .eq(PaperConcept::getPaperId, paperId)
                .eq(PaperConcept::getConceptKey, conceptKey)
                .last("LIMIT 1"));
    }

    private void upsertMapping(Long conceptId, Long symbolId, MappingCandidateDto candidate) {
        ConceptCodeMapping row = new ConceptCodeMapping();
        row.setConceptId(conceptId);
        row.setCodeSymbolId(symbolId);
        row.setConfidence(candidate.totalScore());
        row.setSemanticScore(candidate.semanticScore());
        row.setSymbolScore(candidate.symbolScore());
        row.setKeywordScore(candidate.keywordScore());
        row.setDocumentationScore(candidate.documentationScore());
        row.setVerificationScore(candidate.verificationScore());
        row.setTotalScore(candidate.totalScore());
        row.setMappingStatus(candidate.status());
        row.setDegraded(candidate.degraded());
        row.setVerificationReason(candidate.verificationReason());
        row.setCodeEvidence(candidate.codeEvidence());
        row.setMatchedTokensJson(json(candidate.matchedTokens()));
        row.setNotes("status=" + candidate.status()
                + "; tokens=" + (candidate.matchedTokens() == null ? "[]" : candidate.matchedTokens())
                + "; evidence=" + (candidate.codeEvidence() == null ? "" : candidate.codeEvidence().replace('\n', ' ')));
        conceptCodeMappingMapper.upsert(row);
    }

    private String json(Object value) {
        try {
            return StageSnapshotContract.MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("mapping JSON field serialization failed", e);
        }
    }

    private CodeSymbol resolveSymbol(Long repositoryId, com.paperpilot.api.dto.mapping.SymbolRef ref) {
        if (repositoryId == null || ref == null || ref.commitSha() == null
                || ref.filePath() == null || ref.qualifiedName() == null) {
            return null;
        }
        return codeSymbolMapper.selectOne(new LambdaQueryWrapper<CodeSymbol>()
                .eq(CodeSymbol::getRepositoryId, repositoryId)
                .eq(CodeSymbol::getCommitSha, ref.commitSha())
                .eq(CodeSymbol::getFilePath, ref.filePath())
                .eq(CodeSymbol::getSymbolName, ref.qualifiedName())
                .last("LIMIT 1"));
    }

    private String locationOf(MappingConceptDto concept) {
        StringBuilder sb = new StringBuilder(concept.source());
        if (concept.section() != null) {
            sb.append(" / ").append(concept.section());
        }
        if (concept.page() != null) {
            sb.append(" p").append(concept.page());
        }
        return sb.toString();
    }
}
