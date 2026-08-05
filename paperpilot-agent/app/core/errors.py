"""统一异常与稳定错误码：errorCode / retryable / message，不向客户端返回 Python 堆栈."""


class StageErrorCode:
    """稳定错误码（与 Java 侧错误快照的 errorCode 字段对应）. """

    BAD_REQUEST = "BAD_REQUEST"
    STAGE_FAILED = "STAGE_FAILED"
    WORKER_ERROR = "WORKER_ERROR"


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
