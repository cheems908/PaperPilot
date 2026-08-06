package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.domain.enums.StageExecutionStatus;
import com.paperpilot.api.dto.task.MappingCandidateResult;
import com.paperpilot.api.dto.task.MappingResult;
import com.paperpilot.api.dto.task.PaperInfo;
import com.paperpilot.api.dto.task.RepositoryInfo;
import com.paperpilot.api.dto.task.StageResponse;
import com.paperpilot.api.dto.task.TaskResultResponse;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务结果服务：汇总任务状态、关联论文/仓库、各阶段快照与概念—代码映射.
 *
 * <p>{@code mappingStatus}：{@code NO_MAPPINGS}（无映射）/ {@code CANDIDATES}（含候选）/
 * {@code NEEDS_REVIEW}（全部低置信度）。
 */
@Service
@RequiredArgsConstructor
public class TaskResultService {

    /** 与规则版 Python 一致：总分 ≥ 该阈值视为 CANDIDATE */
    private static final BigDecimal CANDIDATE_THRESHOLD = new BigDecimal("0.4");

    private final AnalysisTaskService analysisTaskService;
    private final StageExecutionService stageExecutionService;
    private final PaperMapper paperMapper;
    private final GitRepositoryMapper repositoryMapper;
    private final PaperConceptMapper paperConceptMapper;
    private final ConceptCodeMappingMapper conceptCodeMappingMapper;
    private final CodeSymbolMapper codeSymbolMapper;

    public TaskResultResponse getResult(Long taskId) {
        AnalysisTask task = analysisTaskService.getTaskOrThrow(taskId);
        List<StageExecution> stages = stageExecutionService.listByTask(taskId);
        List<StageResponse> stageResponses = stages.stream()
                .map(s -> new StageResponse(s.getStage().name(), s.getAttempt(),
                        s.getStatus().name(), responseSnapshot(s), s.getErrorMessage(), s.getUpdatedAt()))
                .toList();

        PaperInfo paper = null;
        if (task.getPaperId() != null) {
            Paper p = paperMapper.selectById(task.getPaperId());
            if (p != null) {
                paper = new PaperInfo(p.getId(), p.getTitle());
            }
        }

        RepositoryInfo repository = null;
        if (task.getRepositoryId() != null) {
            GitRepository r = repositoryMapper.selectById(task.getRepositoryId());
            if (r != null) {
                repository = new RepositoryInfo(r.getId(), r.getGithubUrl());
            }
        }

        // 聚合已完成阶段的快照：stage.name -> snapshot JSON
        Map<String, Object> result = new LinkedHashMap<>();
        for (StageExecution s : stages) {
            if (s.getStatus() == StageExecutionStatus.SUCCEEDED && responseSnapshot(s) != null) {
                result.put(s.getStage().name(), responseSnapshot(s));
            }
        }

        // 概念—代码映射 + 状态
        List<MappingResult> mappings = new ArrayList<>();
        String mappingStatus = "NO_MAPPINGS";
        if (task.getPaperId() != null) {
            List<PaperConcept> concepts = paperConceptMapper.selectList(
                    new LambdaQueryWrapper<PaperConcept>()
                            .eq(PaperConcept::getPaperId, task.getPaperId()));
            boolean hasCandidate = false;
            for (PaperConcept concept : concepts) {
                List<ConceptCodeMapping> ms = conceptCodeMappingMapper.selectList(
                        new LambdaQueryWrapper<ConceptCodeMapping>()
                                .eq(ConceptCodeMapping::getConceptId, concept.getId())
                                .orderByDesc(ConceptCodeMapping::getConfidence));
                List<MappingCandidateResult> candidates = new ArrayList<>();
                for (ConceptCodeMapping m : ms) {
                    CodeSymbol symbol = codeSymbolMapper.selectById(m.getCodeSymbolId());
                    if (symbol == null) {
                        continue;
                    }
                    boolean candidate = m.getConfidence() != null
                            && m.getConfidence().compareTo(CANDIDATE_THRESHOLD) >= 0;
                    hasCandidate |= candidate;
                    candidates.add(new MappingCandidateResult(
                            symbol.getSymbolName(), symbol.getFilePath(), symbol.getLineNumber(),
                            m.getConfidence(), candidate ? "CANDIDATE" : "NEEDS_REVIEW", m.getNotes()));
                }
                if (!candidates.isEmpty()) {
                    mappings.add(new MappingResult(concept.getConceptName(),
                            concept.getEvidenceLocation(), concept.getEvidenceText(), candidates));
                }
            }
            if (!mappings.isEmpty()) {
                mappingStatus = hasCandidate ? "CANDIDATES" : "NEEDS_REVIEW";
            }
        }

        return new TaskResultResponse(taskId, task.getStatus().name(), paper, repository,
                stageResponses, result, mappingStatus, mappings);
    }

    private String responseSnapshot(StageExecution stage) {
        return stage.getOutputSnapshot() != null ? stage.getOutputSnapshot() : stage.getSnapshot();
    }
}
