package com.paperpilot.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.paperpilot.api.domain.entity.CodeSymbol;
import com.paperpilot.api.dto.indexer.FileSymbols;
import com.paperpilot.api.dto.indexer.IndexResult;
import com.paperpilot.api.dto.indexer.SymbolRecord;
import com.paperpilot.api.dto.snapshot.StageSnapshotContract;
import com.paperpilot.api.dto.worker.WorkerStageResponse;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INDEX_CODE 结果持久化：把 Worker 输出的符号列表按稳定键
 * {@code (repository_id, commit_sha, file_path, qualified_name)} 幂等 upsert 到 code_symbol，
 * 返回仅含摘要（file/symbol/warning 数）的快照 JSON（避免整篇符号写进 TEXT 快照）。
 */
@Service
@RequiredArgsConstructor
public class CodeSymbolPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CodeSymbolPersistenceService.class);

    private final CodeSymbolMapper codeSymbolMapper;

    /** 幂等 upsert 并返回摘要快照 JSON。 */
    public String persist(Long repositoryId, WorkerStageResponse response) {
        if (repositoryId == null) {
            throw new RuntimeException("INDEX_CODE 阶段缺少 repositoryId，无法持久化符号");
        }
        IndexResult result = StageSnapshotContract.MAPPER.convertValue(response.output(), IndexResult.class);
        if (result == null || result.commitSha() == null) {
            throw new RuntimeException("INDEX_CODE 输出缺少 commitSha");
        }
        int symbolCount = 0;
        for (FileSymbols file : result.files() == null ? List.<FileSymbols>of() : result.files()) {
            if (file.symbols() == null) {
                continue;
            }
            for (SymbolRecord symbol : file.symbols()) {
                CodeSymbol row = new CodeSymbol();
                row.setRepositoryId(repositoryId);
                row.setCommitSha(result.commitSha());
                row.setFilePath(file.path());
                row.setSymbolName(symbol.qualifiedName()); // 稳定键：qualified name
                row.setSymbolType(symbol.kind());
                row.setLineNumber(symbol.startLine());
                codeSymbolMapper.upsert(row);
                symbolCount++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("commitSha", result.commitSha());
        summary.put("fileCount", result.files() == null ? 0 : result.files().size());
        summary.put("symbolCount", symbolCount);
        summary.put("warningCount", result.warnings() == null ? 0 : result.warnings().size());
        summary.put("warnings", result.warnings() == null ? List.of() : result.warnings());
        log.info("code_symbol upsert repositoryId={} commitSha={} symbolCount={}",
                repositoryId, result.commitSha(), symbolCount);
        try {
            return StageSnapshotContract.MAPPER.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("code symbol summary serialization failed", e);
        }
    }
}
