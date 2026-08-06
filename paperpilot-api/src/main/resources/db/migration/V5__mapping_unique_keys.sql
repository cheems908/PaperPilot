-- T3-05：映射幂等 upsert 所需唯一键
ALTER TABLE paper_concept
    ADD UNIQUE KEY uk_concept_paper_name (paper_id, concept_name);
ALTER TABLE concept_code_mapping
    ADD UNIQUE KEY uk_mapping_concept_symbol (concept_id, code_symbol_id);
