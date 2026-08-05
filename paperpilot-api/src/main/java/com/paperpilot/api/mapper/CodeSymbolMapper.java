package com.paperpilot.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paperpilot.api.domain.entity.CodeSymbol;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** {@link CodeSymbol} 的 MyBatis-Plus Mapper. */
@Mapper
public interface CodeSymbolMapper extends BaseMapper<CodeSymbol> {

    /**
     * 幂等 upsert：稳定键 (repository_id, commit_sha, file_path, symbol_name)。
     * 重复执行同一 commit 不会产生重复记录（ON DUPLICATE KEY UPDATE 刷新类型与行号）。
     */
    @Insert("INSERT INTO code_symbol (repository_id, commit_sha, file_path, symbol_name, symbol_type, line_number) "
            + "VALUES (#{s.repositoryId}, #{s.commitSha}, #{s.filePath}, #{s.symbolName}, #{s.symbolType}, #{s.lineNumber}) "
            + "ON DUPLICATE KEY UPDATE symbol_type = VALUES(symbol_type), line_number = VALUES(line_number)")
    int upsert(@Param("s") CodeSymbol symbol);
}
