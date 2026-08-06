import copy

from tools.benchmark_evaluator import evaluate, render_markdown


COMMIT = "a" * 40


def _gold():
    return {
        "schemaVersion": 1, "benchmarkId": "tiny", "repository": {"commitSha": COMMIT},
        "concepts": [
            {"id": "PT-01", "concept": "Patching", "certainty": "CONFIRMED",
             "mappings": [{"filePath": "model.py", "qualifiedName": "Model.patch"}]},
            {"id": "PT-02", "concept": "Shared encoder", "certainty": "CONFIRMED",
             "mappings": [
                 {"filePath": "model.py", "qualifiedName": "Encoder.forward"},
                 {"filePath": "layers.py", "qualifiedName": "Attention.forward"},
             ]},
            {"id": "PT-03", "concept": "Training loss", "certainty": "NO_EXPLICIT_IMPLEMENTATION",
             "mappings": []},
        ],
    }


def _candidate(path, name, status="VERIFIED"):
    return {"symbolRef": {"commitSha": COMMIT, "filePath": path, "qualifiedName": name, "startLine": 2},
            "status": status}


def _result():
    evidence = {"source": "heading", "page": 1, "evidenceText": "paper evidence"}
    return {"metadata": {"label": "enhanced", "stageDurationsMs": {"MAP": 12}, "llmTokens": 8},
            "concepts": [
                {"conceptId": "pc_not_a_benchmark_id", "term": "Patching", **evidence,
                 "candidates": [_candidate("model.py", "Model.patch")]},
                {"term": "Shared encoder", **evidence,
                 "candidates": [_candidate("wrong.py", "Wrong.forward", "NEEDS_REVIEW"),
                                _candidate("model.py", "Encoder.forward")]},
                {"conceptId": "pc_abstention", "term": "Training loss", **evidence,
                 "decision": "ABSTAINED", "abstentionReason": "NO_STABLE_SYMBOL", "candidates": []},
            ]}


def test_metrics_have_explicit_denominators_and_expected_values():
    report = evaluate(_gold(), _result())
    assert report["denominators"] == {
        "confirmedConcepts": 2, "confirmedRelevantSymbols": 3,
        "matchedConfirmedConcepts": 2, "evaluatedCandidates": 3,
        "abstentionCases": 1, "stageDurations": 1,
    }
    assert report["metrics"]["precisionAt1"] == 0.5
    assert report["metrics"]["conceptExtractionCoverage"] == 1.0
    assert report["metrics"]["recallAt3"] == 0.75
    assert report["metrics"]["mrr"] == 0.75
    assert report["metrics"]["evidenceCompleteness"] == 1.0
    assert report["metrics"]["needsReviewRatio"] == 0.333333
    assert report["metrics"]["abstentionAccuracy"] == 1.0


def test_repeated_evaluation_is_identical_and_does_not_mutate_input():
    result = _result()
    original = copy.deepcopy(result)
    assert evaluate(_gold(), result) == evaluate(_gold(), result)
    assert result == original


def test_missing_evidence_and_error_categories_are_reported():
    result = _result()
    result["concepts"][0].pop("page")
    result["concepts"][1]["candidates"] = []
    report = evaluate(_gold(), result)
    assert report["metrics"]["evidenceCompleteness"] == 0.0
    assert report["errorSummary"]["RETRIEVAL_EMPTY"] == 1
    assert "规则版" in render_markdown([{**report, "label": "规则版"}])


def test_unmatched_concepts_still_count_toward_evidence_denominator():
    result = _result()
    result["concepts"].append({"term": "unmatched", "source": "heading", "page": 2,
                               "evidenceText": "complete evidence",
                               "candidates": [_candidate("extra.py", "Extra.run")]})
    report = evaluate(_gold(), result)
    assert report["denominators"]["evaluatedCandidates"] == 4
    assert report["metrics"]["evidenceCompleteness"] == 1.0


def test_result_api_mentions_are_valid_paper_evidence_anchor():
    result = _result()
    concept = result["concepts"][0]
    concept.pop("page")
    concept.pop("source")
    concept["mentions"] = [{"section": "Model", "page": 4, "paragraphId": "4.2",
                            "evidenceText": "paper evidence"}]
    assert evaluate(_gold(), result)["metrics"]["evidenceCompleteness"] == 1.0


def test_missing_concept_is_not_counted_as_abstention():
    result = _result()
    result["concepts"] = result["concepts"][:2]
    report = evaluate(_gold(), result)
    assert report["metrics"]["abstentionAccuracy"] == 0.0
    assert report["errorSummary"]["TERM_OR_CONCEPT_EXTRACTION"] == 1


def test_empty_candidates_without_explicit_decision_is_not_abstention():
    result = _result()
    abstention = result["concepts"][2]
    abstention.pop("decision")
    abstention.pop("abstentionReason")
    report = evaluate(_gold(), result)
    assert report["metrics"]["abstentionAccuracy"] == 0.0
    assert report["errorSummary"]["IMPLICIT_OR_INVALID_ABSTENTION"] == 1


def test_candidate_must_explicitly_carry_frozen_commit():
    result = _result()
    result["concepts"][0]["candidates"][0]["symbolRef"].pop("commitSha")
    report = evaluate(_gold(), result)
    assert report["metrics"]["evidenceCompleteness"] < 1.0
    assert report["metrics"]["recallAt1"] == 0.0


def test_benchmark_id_is_never_used_as_production_alignment_key():
    result = _result()
    result["concepts"][0]["conceptId"] = "PT-01"
    result["concepts"][0]["term"] = "unrelated"
    report = evaluate(_gold(), result)
    assert report["metrics"]["conceptExtractionCoverage"] == 0.5
