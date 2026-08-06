-- T5-04C: stable production concept identity and lossless mapping results.
ALTER TABLE paper_concept
    ADD COLUMN concept_key CHAR(27) NULL AFTER paper_id,
    ADD COLUMN aliases_json JSON NULL AFTER concept_name,
    ADD COLUMN mentions_json JSON NULL AFTER aliases_json,
    ADD COLUMN extractor_version VARCHAR(64) NULL AFTER mentions_json,
    ADD COLUMN decision VARCHAR(32) NULL AFTER extractor_version,
    ADD COLUMN abstention_reason VARCHAR(128) NULL AFTER decision;

UPDATE paper_concept
SET concept_key = CONCAT('legacy_', SUBSTRING(SHA2(CONCAT(paper_id, '\n', concept_name), 256), 1, 20)),
    extractor_version = 'legacy-v1',
    decision = 'MAPPED'
WHERE concept_key IS NULL;

ALTER TABLE paper_concept
    MODIFY COLUMN concept_key CHAR(27) NOT NULL,
    DROP INDEX uk_concept_paper_name,
    ADD UNIQUE KEY uk_concept_paper_key (paper_id, concept_key);

ALTER TABLE concept_code_mapping
    ADD COLUMN semantic_score DECIMAL(6,5) NULL AFTER confidence,
    ADD COLUMN symbol_score DECIMAL(6,5) NULL AFTER semantic_score,
    ADD COLUMN keyword_score DECIMAL(6,5) NULL AFTER symbol_score,
    ADD COLUMN documentation_score DECIMAL(6,5) NULL AFTER keyword_score,
    ADD COLUMN verification_score DECIMAL(6,5) NULL AFTER documentation_score,
    ADD COLUMN total_score DECIMAL(6,5) NULL AFTER verification_score,
    ADD COLUMN mapping_status VARCHAR(32) NULL AFTER total_score,
    ADD COLUMN degraded BOOLEAN NULL AFTER mapping_status,
    ADD COLUMN verification_reason TEXT NULL AFTER degraded,
    ADD COLUMN code_evidence TEXT NULL AFTER verification_reason,
    ADD COLUMN matched_tokens_json JSON NULL AFTER code_evidence;

UPDATE concept_code_mapping
SET total_score = confidence,
    mapping_status = CASE WHEN confidence >= 0.4 THEN 'CANDIDATE' ELSE 'NEEDS_REVIEW' END,
    degraded = TRUE
WHERE total_score IS NULL;
