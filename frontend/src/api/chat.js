import axios from 'axios'
import { createSseParser, parseAgentEvent } from './sse'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  adapter: 'fetch',
  responseType: 'stream',
  headers: { Accept: 'text/event-stream' },
})

export async function sendChatStream(message, { onEvent, signal } = {}) {
  const controller = new AbortController()
  const abort = () => controller.abort(signal.reason)
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()
  // Cover both connection setup and body reads; the backend allows 300 seconds.
  const timer = setTimeout(() => controller.abort(
    Object.assign(new Error('流式请求超时'), { code: 'ETIMEDOUT' }),
  ), 310000)
  let reader
  let completed = false
  let received = false
  try {
    const { data, headers } = await client.get('/agent/chat/stream', {
      params: { message }, signal: controller.signal,
    })
    reader = data.getReader()
    if (!headers['content-type']?.toLowerCase().startsWith('text/event-stream')) {
      throw new Error('服务未返回 SSE 流，请确认后端流式接口已启动。')
    }
    const decoder = new TextDecoder('utf-8')
    const parse = createSseParser((event) => {
      if (completed) return
      const result = parseAgentEvent(event)
      if (result.type === 'final' && !result.content) {
        throw new Error('智能体暂时没有返回最终内容，请重试。')
      }
      received = true
      completed = result.type === 'final'
      onEvent?.(result)
    })
    while (!completed) {
      const { value, done } = await reader.read()
      if (done) {
        parse(decoder.decode())
        break
      }
      parse(decoder.decode(value, { stream: true }))
    }
    if (!completed) {
      throw new Error(received
        ? '连接已中断，尚未收到最终结果。已保留收到的步骤，请稍后重试。'
        : '智能体暂时没有返回内容，请重试。')
    }
  } catch (error) {
    if (controller.signal.aborted) throw controller.signal.reason || error
    // Non-2xx fetch responses may also carry a readable body.
    await error.response?.data?.cancel?.().catch(() => {})
    if (error instanceof TypeError && received) {
      throw new Error('连接已中断，尚未收到最终结果。已保留收到的步骤，请稍后重试。')
    }
    throw error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
    controller.abort()
    await reader?.cancel().catch(() => {})
    reader?.releaseLock()
  }
}

export function getChatError(error) {
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return '这次规划用时较长，等待已超时。请稍后重试。'
  }
  if (axios.isAxiosError(error)) {
    if (!error.response || error.response.status >= 500) {
      return '暂时无法连接出行助手，请确认后端服务已启动，稍后重试。'
    }
    return `请求未成功（${error.response.status}），请稍后重试。`
  }
  return error.message || '路线规划暂时遇到问题，请重试。'
}
