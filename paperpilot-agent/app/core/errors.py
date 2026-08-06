"""统一异常与稳定错误码：errorCode / retryable / message，不向客户端返回 Python 堆栈."""


class StageErrorCode:
    """稳定错误码（与 Java 侧错误快照的 errorCode 字段对应）. """

    BAD_REQUEST = "BAD_REQUEST"
    STAGE_FAILED = "STAGE_FAILED"
    WORKER_ERROR = "WORKER_ERROR"

    # 论文解析阶段（T3-02）
    INVALID_PAPER_INPUT = "INVALID_PAPER_INPUT"
    FILE_NOT_FOUND = "FILE_NOT_FOUND"
    FILE_HASH_MISMATCH = "FILE_HASH_MISMATCH"
    PATH_OUTSIDE_STORAGE_ROOT = "PATH_OUTSIDE_STORAGE_ROOT"
    INVALID_PDF = "INVALID_PDF"
    GROBID_UNAVAILABLE = "GROBID_UNAVAILABLE"
    PAPER_PARSE_FAILED = "PAPER_PARSE_FAILED"

    # 仓库克隆阶段（T3-03）
    INVALID_GITHUB_URL = "INVALID_GITHUB_URL"
    REPOSITORY_NOT_FOUND = "REPOSITORY_NOT_FOUND"
    UNSUPPORTED_REPOSITORY = "UNSUPPORTED_REPOSITORY"
    REPOSITORY_TOO_LARGE = "REPOSITORY_TOO_LARGE"
    GITHUB_TEMPORARY_FAILURE = "GITHUB_TEMPORARY_FAILURE"
    CLONE_TIMEOUT = "CLONE_TIMEOUT"
    INVALID_WORKSPACE_REF = "INVALID_WORKSPACE_REF"

    # 代码索引阶段（T3-04）
    INVALID_INDEX_INPUT = "INVALID_INDEX_INPUT"
    CODE_INDEX_FAILED = "CODE_INDEX_FAILED"

    # 概念—代码映射阶段（T3-05/T3-06）
    INVALID_MAPPING_INPUT = "INVALID_MAPPING_INPUT"
    MAPPING_VERIFICATION_FAILED = "MAPPING_VERIFICATION_FAILED"


class StageServiceError(Exception):
    """阶段服务异常：携带稳定错误码、可重试标记与面向客户端的安全消息。"""

    def __init__(
        self,
        error_code: str,
        message: str,
        retryable: bool = False,
        status_code: int = 500,
    ):
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.retryable = retryable
        self.status_code = status_code
