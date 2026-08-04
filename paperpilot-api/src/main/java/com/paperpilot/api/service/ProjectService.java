package com.paperpilot.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.common.ApiException;
import com.paperpilot.api.common.ErrorCode;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.entity.StageExecution;
import com.paperpilot.api.dto.project.ProjectCreateRequest;
import com.paperpilot.api.dto.project.ProjectResponse;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;

/**
 * 项目服务：CRUD + 级联删除.
 *
 * <p>删除项目时按外键依赖顺序级联清理：stage_execution → analysis_task →
 * concept_code_mapping → paper_concept / code_symbol → paper / repository → project。
 * {@code file} 为独立资源，不受项目删除影响。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final StageExecutionMapper stageExecutionMapper;
    private final PaperMapper paperMapper;
    private final PaperConceptMapper paperConceptMapper;
    private final GitRepositoryMapper repositoryMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final ConceptCodeMappingMapper conceptCodeMappingMapper;

    @Transactional
    public ProjectResponse create(ProjectCreateRequest req) {
        Project project = new Project();
        project.setName(req.name());
        project.setDescription(req.description());
        projectMapper.insert(project);
        return toResponse(project);
    }

    public List<ProjectResponse> list() {
        return projectMapper.selectList(
                        new LambdaQueryWrapper<Project>().orderByDesc(Project::getId))
                .stream().map(this::toResponse).toList();
    }

    public ProjectResponse get(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);

        List<Long> taskIds = ids(analysisTaskMapper.selectList(
                new LambdaQueryWrapper<AnalysisTask>().eq(AnalysisTask::getProjectId, id)),
                AnalysisTask::getId);
        List<Long> paperIds = ids(paperMapper.selectList(
                new LambdaQueryWrapper<Paper>().eq(Paper::getProjectId, id)), Paper::getId);
        List<Long> repositoryIds = ids(repositoryMapper.selectList(
                new LambdaQueryWrapper<GitRepository>().eq(GitRepository::getProjectId, id)),
                GitRepository::getId);

        // 1. 任务下的阶段执行
        if (!taskIds.isEmpty()) {
            stageExecutionMapper.delete(new LambdaQueryWrapper<StageExecution>()
                    .in(StageExecution::getTaskId, taskIds));
        }
        // 2. 概念-代码映射（引用 paper_concept 与 code_symbol）
        if (!paperIds.isEmpty()) {
            List<Long> conceptIds = ids(paperConceptMapper.selectList(
                    new LambdaQueryWrapper<PaperConcept>().in(PaperConcept::getPaperId, paperIds)),
                    PaperConcept::getId);
            if (!conceptIds.isEmpty()) {
                conceptCodeMappingMapper.delete(new LambdaQueryWrapper<ConceptCodeMapping>()
                        .in(ConceptCodeMapping::getConceptId, conceptIds));
            }
        }
        if (!repositoryIds.isEmpty()) {
            List<Long> symbolIds = ids(codeSymbolMapper.selectList(
                    new LambdaQueryWrapper<CodeSymbol>().in(CodeSymbol::getRepositoryId, repositoryIds)),
                    CodeSymbol::getId);
            if (!symbolIds.isEmpty()) {
                conceptCodeMappingMapper.delete(new LambdaQueryWrapper<ConceptCodeMapping>()
                        .in(ConceptCodeMapping::getCodeSymbolId, symbolIds));
            }
        }
        // 3. 概念与符号
        if (!paperIds.isEmpty()) {
            paperConceptMapper.delete(new LambdaQueryWrapper<PaperConcept>()
                    .in(PaperConcept::getPaperId, paperIds));
        }
        if (!repositoryIds.isEmpty()) {
            codeSymbolMapper.delete(new LambdaQueryWrapper<CodeSymbol>()
                    .in(CodeSymbol::getRepositoryId, repositoryIds));
        }
        // 4. 任务（引用 paper/repository/project）
        if (!taskIds.isEmpty()) {
            analysisTaskMapper.deleteBatchIds(taskIds);
        }
        // 5. 论文与仓库（引用 project）
        if (!paperIds.isEmpty()) {
            paperMapper.deleteBatchIds(paperIds);
        }
        if (!repositoryIds.isEmpty()) {
            repositoryMapper.deleteBatchIds(repositoryIds);
        }
        // 6. 项目
        projectMapper.deleteById(id);
    }

    public Project getOrThrow(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private static <T> List<Long> ids(List<T> entities, Function<T, Long> idGetter) {
        return entities.stream().map(idGetter).toList();
    }

    private ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getName(),
                project.getDescription(), project.getCreatedAt());
    }
}
