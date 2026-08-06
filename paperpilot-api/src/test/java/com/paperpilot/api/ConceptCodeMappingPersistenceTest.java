package com.paperpilot.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.domain.entity.ConceptCodeMapping;
import com.paperpilot.api.domain.entity.GitRepository;
import com.paperpilot.api.domain.entity.Paper;
import com.paperpilot.api.domain.entity.PaperConcept;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.service.MappingPersistenceService;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MAP_CONCEPTS 结果持久化：概念写 paper_concept、映射幂等写 concept_code_mapping，
 * 重复执行不产生重复记录；代码坐标经 code_symbol 稳定键解析.
 */
@Testcontainers
class ConceptCodeMappingPersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void repeatedPersistDoesNotDuplicateMappings() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            GitRepositoryMapper repositoryMapper = session.getMapper(GitRepositoryMapper.class);
            PaperMapper paperMapper = session.getMapper(PaperMapper.class);
            CodeSymbolMapper codeSymbolMapper = session.getMapper(CodeSymbolMapper.class);
            PaperConceptMapper conceptMapper = session.getMapper(PaperConceptMapper.class);
            ConceptCodeMappingMapper mappingMapper = session.getMapper(ConceptCodeMappingMapper.class);
            MappingPersistenceService service = new MappingPersistenceService(
                    conceptMapper, mappingMapper, codeSymbolMapper);

            Project project = new Project();
            project.setName("p");
            projectMapper.insert(project);

            GitRepository repo = new GitRepository();
            repo.setProjectId(project.getId());
            repo.setGithubUrl("https://github.com/paperpilot/patchtst");
            repositoryMapper.insert(repo);

            Paper paper = new Paper();
            paper.setProjectId(project.getId());
            paper.setTitle("PatchTST");
            paper.setPdfUrl("http://example.com/paper.pdf");
            paperMapper.insert(paper);

            // 预置一个与候选 ref 匹配的 code_symbol（INDEX 阶段已写入）
            CodeSymbol symbol = new CodeSymbol();
            symbol.setRepositoryId(repo.getId());
            symbol.setCommitSha("f".repeat(40));
            symbol.setFilePath("model.py");
            symbol.setSymbolName("PatchTST");
            symbol.setSymbolType("class");
            symbol.setLineNumber(5);
            codeSymbolMapper.insert(symbol);

            WorkerStageResponse response = mappingResponse();

            String summary = service.persist(paper.getId(), repo.getId(), response);
            service.persist(paper.getId(), repo.getId(), response); // 重复执行

            // 概念与映射均不重复（按当前论文/概念范围计数，容器跨方法共享）
            PaperConcept concept = conceptMapper.selectOne(new LambdaQueryWrapper<PaperConcept>()
                    .eq(PaperConcept::getPaperId, paper.getId()));
            assertThat(concept).isNotNull();
            assertThat(conceptMapper.selectCount(new LambdaQueryWrapper<PaperConcept>()
                    .eq(PaperConcept::getPaperId, paper.getId()))).isEqualTo(1);
            assertThat(mappingMapper.selectCount(new LambdaQueryWrapper<ConceptCodeMapping>()
                    .eq(ConceptCodeMapping::getConceptId, concept.getId()))).isEqualTo(1);
            assertThat(summary).contains("\"conceptCount\":1").contains("\"mappingCount\":1");

            ConceptCodeMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<ConceptCodeMapping>()
                    .eq(ConceptCodeMapping::getConceptId, concept.getId()));
            assertThat(mapping.getCodeSymbolId()).isEqualTo(symbol.getId());
            assertThat(mapping.getConfidence()).isEqualByComparingTo("0.2");
        }
    }

    @Test
    void unresolvableSymbolRefIsSkipped() throws Exception {
        DataSource ds = TestSupport.dataSource(MYSQL);
        Flyway.configure().dataSource(ds).load().migrate();

        try (SqlSession session = TestSupport.buildFactory(ds).openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            PaperMapper paperMapper = session.getMapper(PaperMapper.class);
            PaperConceptMapper conceptMapper = session.getMapper(PaperConceptMapper.class);
            ConceptCodeMappingMapper mappingMapper = session.getMapper(ConceptCodeMappingMapper.class);
            CodeSymbolMapper codeSymbolMapper = session.getMapper(CodeSymbolMapper.class);
            MappingPersistenceService service = new MappingPersistenceService(
                    conceptMapper, mappingMapper, codeSymbolMapper);

            Project project = new Project();
            project.setName("p");
            projectMapper.insert(project);
            Paper paper = new Paper();
            paper.setProjectId(project.getId());
            paper.setTitle("PatchTST");
            paper.setPdfUrl("http://example.com/paper.pdf");
            paperMapper.insert(paper);

            // 无任何 code_symbol：候选 ref 无法关联 → 概念仍写，映射被跳过
            String summary = service.persist(paper.getId(), 999L, mappingResponse());
            PaperConcept concept = conceptMapper.selectOne(new LambdaQueryWrapper<PaperConcept>()
                    .eq(PaperConcept::getPaperId, paper.getId()));
            assertThat(concept).isNotNull();
            assertThat(mappingMapper.selectCount(new LambdaQueryWrapper<ConceptCodeMapping>()
                    .eq(ConceptCodeMapping::getConceptId, concept.getId()))).isZero();
            assertThat(summary).contains("\"mappingCount\":0");
        }
    }

    private WorkerStageResponse mappingResponse() {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("commitSha", "f".repeat(40));
        output.put("concepts", List.of(Map.of(
                "term", "channel independence", "source", "heading",
                "evidenceText", "The model applies channel independence.",
                "candidates", List.of(Map.of(
                        "symbolRef", Map.of("filePath", "model.py", "qualifiedName", "PatchTST",
                                "name", "PatchTST", "startLine", 5, "commitSha", "f".repeat(40)),
                        "symbolScore", 0, "keywordScore", 0, "documentationScore", 1,
                        "totalScore", 0.2, "status", "NEEDS_REVIEW",
                        "matchedTokens", List.of("channel", "independence"),
                        "codeEvidence", "docstring")))));
        output.put("stats", Map.of("conceptCount", 1, "candidateCount", 1, "needsReviewCount", 1));
        return new WorkerStageResponse(WorkerStageResponse.SCHEMA_VERSION, true,
                output, List.of(), Map.of(), "0.3.0-mapping");
    }
}
