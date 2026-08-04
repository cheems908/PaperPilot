import { apiRequest } from './api.js'
import { isTerminalStatus, isTerminalState } from './taskEventsPolicy.js'

/**
 * SSE 连接池 — 管理分析任务的实时进度推送。
 * 支持指数退避重连，终端状态自动释放。
 */
export function createTaskStreams({ onActiveChange = () => {} } = {}) {
  const streams = new Map()
  const keyOf = (taskId) => `task:${taskId}`
  const publish = () => onActiveChange([...streams.values()]
    .map(({ taskId }) => ({ taskId })))

  const stop = (taskId) => {
    const key = keyOf(taskId)
    const entry = streams.get(key)
    if (!entry) return
    entry.controller.abort()
    streams.delete(key)
    publish()
  }

  const stopAll = () => {
    if (!streams.size) return
    for (const { controller } of streams.values()) controller.abort()
    streams.clear()
    publish()
  }

  const start = (taskId, path, onEvent, onError) => {
    stop(taskId)
    const key = keyOf(taskId)
    const controller = new AbortController()
    streams.set(key, { controller, taskId })
    publish()
    let reconnectAttempt = 0

    const release = () => {
      if (streams.get(key)?.controller !== controller) return
      streams.delete(key)
      publish()
    }

    const run = async () => {
      while (!controller.signal.aborted && streams.get(key)?.controller === controller) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          if (!response.ok) {
            const error = new Error(
              (await response.text()) || `事件流连接失败（HTTP ${response.status}）`)
            error.status = response.status
            if (isTerminalStatus(response.status)) {
              release()
              onError?.(error, reconnectAttempt + 1, true)
              return
            }
            throw error
          }
          if (!response.body) throw new Error('服务端未返回事件流')

          const terminal = await consumeStream(response.body, async event => {
            reconnectAttempt = 0
            await onEvent(event)
          }, controller.signal)
          if (terminal) {
            release()
            return
          }
        } catch (error) {
          if (controller.signal.aborted) return
          onError?.(error, reconnectAttempt + 1)
        }
        const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
        await waitForRetry(delay, controller.signal)
      }
    }

    run().catch(error => {
      if (controller.signal.aborted) return
      release()
      onError?.(error, reconnectAttempt + 1, true)
    })
  }

  return { has: (taskId) => streams.has(keyOf(taskId)), start, stop, stopAll }
}

function waitForRetry(delay, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise(resolve => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })
    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        await onEvent(event)
        if (isTerminalState(event.state)) return true
      }
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}
