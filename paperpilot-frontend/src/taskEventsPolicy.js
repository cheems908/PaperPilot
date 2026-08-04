/** SSE 终端状态：这些 HTTP 状态码不会自愈，不应重连。 */
export function isTerminalStatus(status) {
  return status === 400 || status === 401 || status === 403 || status === 404
}

/** SSE 事件中的终端状态：任务已完成或失败，关闭连接。 */
export function isTerminalState(state) {
  return state === 'COMPLETED' || state === 'FAILED'
}
