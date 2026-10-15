import { test, expect } from '@playwright/test'
import { createServer } from 'node:http'
import { createSseParser } from '../src/api/sse.js'

const sse = (text) => `${text.split('\n').map((line) => `data:${line}`).join('\n')}\n\n`
const finalReply = (text) => ({ contentType: 'text/event-stream', body: sse(`模型最终的输出为：${text}`) })
const success = (data) => ({ code: 200, message: '操作成功', data })
const history = (question, answer, steps = []) => [
  { id: 'u1', turnId: 't1', role: 'user', content: question, steps: [] },
  { id: 'a1', turnId: 't1', role: 'assistant', content: answer, steps },
]

async function mockBackend(page, sessions = [], messages = {}) {
  const state = { sessions: [...sessions], messages: { ...messages }, requests: [] }
  state.saveReply = (request, answer, steps = []) => {
    const params = new URL(request.url()).searchParams
    const id = params.get('chatId')
    const question = params.get('message')
    const session = state.sessions.find((item) => item.id === id) || { id, title: question.slice(0, 20), status: 1 }
    state.sessions = [session, ...state.sessions.filter((item) => item.id !== id)]
    const previous = state.messages[id] || []
    const turnId = `turn-${previous.length}`
    state.messages[id] = [...previous, ...history(question, answer, steps).map((message) => ({ ...message, id: `${turnId}-${message.id}`, turnId }))]
  }
  state.onStream = (route, complete) => complete('路线规划结果')
  await page.route(/\/api\/chat(?:\/|\?|$)/, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    state.requests.push({ method: request.method(), path: url.pathname, params: url.searchParams })
    if (url.pathname === '/api/chat/stream') {
      return state.onStream(route, (answer, steps = []) => {
        state.saveReply(request, answer, steps)
        return route.fulfill(finalReply(answer))
      })
    }
    if (url.pathname === '/api/chat/messages') {
      if (state.onHistory) return state.onHistory(route, url.searchParams.get('chatId'))
      return route.fulfill({ status: state.historyFailure ? 503 : 200, json: state.historyFailure
        ? { code: 503, message: '暂不可用' } : success(state.messages[url.searchParams.get('chatId')] || []) })
    }
    if (url.pathname !== '/api/chat') return route.fulfill({ status: 404 })
    if (request.method() === 'GET') return route.fulfill({ status: state.listFailure ? 503 : 200,
      json: state.listFailure ? { code: 503, message: '暂不可用' } : success(state.sessions) })
    if (request.method() === 'DELETE') {
      if (state.deleteFailure) return route.fulfill({ json: { code: 500, message: '删除失败' } })
      const id = url.searchParams.get('chatId')
      state.sessions = state.sessions.filter((item) => item.id !== id)
      delete state.messages[id]
      return route.fulfill({ json: success(null) })
    }
    if (request.method() === 'PUT') {
      if (state.renameFailure) return route.fulfill({ json: { code: 500, message: '更新失败' } })
      state.sessions.find((item) => item.id === url.searchParams.get('chatId')).title = url.searchParams.get('title')
      return route.fulfill({ json: success(null) })
    }
    return route.fulfill({ status: 405 })
  })
  return state
}
const composer = (page) => page.getByRole('textbox', { name: '描述你的地铁出行需求' })
async function send(page, text) {
  await composer(page).fill(text)
  await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeEnabled()
  await composer(page).press('Enter')
}

test('new stream route encodes preferences, renders safely and restores from server', async ({ page }) => {
  const backend = await mockBackend(page)
  backend.onStream = (route, complete) => complete('## 推荐路线\n\n请乘坐 **地铁**。\n\n<script>window.injected = true</script><img src=x onerror="window.injected = true">')
  await page.goto('/')
  await page.getByRole('button', { name: /从深大到深圳北站/ }).click()
  await page.getByRole('button', { name: '少换乘', exact: true }).click()
  await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByRole('heading', { name: '推荐路线' })).toBeVisible()
  const request = backend.requests.find((item) => item.path.endsWith('/stream'))
  expect(request.method).toBe('GET')
  expect(request.params.get('message')).toBe('在深圳，从深大地铁站到深圳北站怎么坐地铁？\n出行偏好：少换乘。')
  expect(request.params.get('chatId')).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/)
  expect(await page.evaluate(() => window.injected)).toBeUndefined()
  await expect(page.locator('.markdown img, .markdown script')).toHaveCount(0)
  await page.evaluate(() => localStorage.clear())
  await page.reload()
  await expect(page.getByRole('heading', { name: '推荐路线' })).toBeVisible()
})

test('prevents duplicate submissions and retries without duplicating the user message', async ({ page }) => {
  const backend = await mockBackend(page)
  let requests = 0
  let release
  const waiting = new Promise((resolve) => { release = resolve })
  backend.onStream = async (route, complete) => {
    if (++requests === 1) { await waiting; return route.fulfill({ status: 503, body: 'Unavailable' }) }
    return complete('已重新规划路线。')
  }
  await page.goto('/')
  await send(page, '深圳深大到深圳北站')
  await expect(page.getByText('正在为你规划路线…')).toBeVisible()
  await composer(page).fill('不能重复提交')
  await composer(page).press('Enter')
  await expect(page.getByRole('button', { name: '正在等待回复' })).toBeDisabled()
  release()
  await expect(page.getByText(/暂时无法连接出行助手/)).toBeVisible()
  expect(requests).toBe(1)
  await page.getByRole('button', { name: '重新尝试', exact: true }).click()
  await expect(page.getByText('已重新规划路线。')).toBeVisible()
  await expect(page.locator('.user-bubble')).toHaveCount(1)
  expect(requests).toBe(2)
})

test('handles empty replies and preserves punctuation and newlines', async ({ page }) => {
  const backend = await mockBackend(page)
  backend.onStream = (route) => route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' })
  await page.goto('/')
  await send(page, '深圳 A&B + 北站？\n下一行')
  await expect(page.getByText('智能体暂时没有返回内容，请重试。')).toBeVisible()
  expect(backend.requests.find((item) => item.path.endsWith('/stream')).params.get('message')).toBe('深圳 A&B + 北站？\n下一行')
})

test('searches, switches, confirms deletion and stays deleted after reload', async ({ page }) => {
  const backend = await mockBackend(page)
  await page.goto('/')
  await send(page, '深圳北站怎么走')
  await expect(page.getByText('路线规划结果')).toBeVisible()
  await page.getByRole('button', { name: /开启新对话/ }).click()
  await expect(page.getByRole('heading', { name: '下一站，想去哪里？' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('不存在')
  await expect(page.getByText('没有找到相关对话')).toBeVisible()
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('深圳')
  await page.getByRole('button', { name: '深圳北站怎么走', exact: true }).click()
  await expect(page.getByText('路线规划结果')).toBeVisible()
  await page.getByRole('button', { name: '删除对话：深圳北站怎么走' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(backend.requests.some((item) => item.method === 'DELETE')).toBe(false)
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByRole('heading', { name: '下一站，想去哪里？' })).toBeVisible()
  expect(backend.requests.find((item) => item.method === 'DELETE').params.get('chatId')).toBeTruthy()
  await page.reload()
  await expect(page.getByText('每一次出发，都从这里开始')).toBeVisible()
})

test('delete business failure keeps the conversation, and cancelling sends no request', async ({ page }) => {
  const backend = await mockBackend(page, [{ id: 'chat-1', title: '深圳北站' }], { 'chat-1': history('去北站', '已有路线') })
  backend.deleteFailure = true
  await page.goto('/')
  await expect(page.getByText('已有路线')).toBeVisible()
  await page.getByRole('button', { name: '删除对话：深圳北站' }).click()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(backend.requests.some((item) => item.method === 'DELETE')).toBe(false)
  await page.getByRole('button', { name: '删除对话：深圳北站' }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText('删除对话失败。删除失败')).toBeVisible()
  await expect(page.getByText('已有路线')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: '删除对话：深圳北站' })).toBeVisible()
})

test('renames through server, handles business failure and restores after reload', async ({ page }) => {
  const backend = await mockBackend(page, [{ id: 'chat-1', title: '原来的标题' }])
  backend.renameFailure = true
  await page.goto('/')
  await page.getByRole('button', { name: '重命名对话：原来的标题' }).click()
  await page.getByRole('textbox', { name: '会话标题' }).fill('机场 & 北站 + 换乘')
  await page.screenshot({ path: 'test-results/rename.png', fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '保存标题', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('更新失败')
  await expect(page.getByRole('button', { name: '原来的标题', exact: true })).toBeVisible()
  backend.renameFailure = false
  await page.getByRole('button', { name: '保存标题', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(backend.requests.find((item) => item.method === 'PUT').params.get('title')).toBe('机场 & 北站 + 换乘')
  await page.reload()
  await expect(page.getByRole('button', { name: '机场 & 北站 + 换乘', exact: true })).toBeVisible()
})

test('server history replaces stale browser storage and continues with the same chatId', async ({ page }) => {
  const backend = await mockBackend(page, [{ id: 'server-chat', title: '数据库中的会话' }], {
    'server-chat': history('之前的问题', '之前的回答', [{ number: 1, content: '查询历史路线' }]),
  })
  await page.addInitScript(() => localStorage.setItem('routepilot.conversations.v1', JSON.stringify([{ id: 'stale', title: '旧本地会话', messages: [] }])))
  await page.goto('/')
  await expect(page.getByText('之前的回答')).toBeVisible()
  await page.screenshot({ path: 'test-results/history.png', fullPage: true })
  await expect(page.getByRole('button', { name: '旧本地会话', exact: true })).toHaveCount(0)
  await send(page, '有没有少换乘的方案？')
  await expect(page.getByText('路线规划结果')).toBeVisible()
  expect(backend.requests.find((item) => item.path.endsWith('/stream')).params.get('chatId')).toBe('server-chat')
  await page.reload()
  await expect(page.locator('.user-bubble')).toHaveCount(2)
})

test('list and history failures support retry and block submission until history loads', async ({ page }) => {
  const backend = await mockBackend(page, [{ id: 'chat-1', title: '历史会话' }], { 'chat-1': history('提问', '历史回复') })
  backend.listFailure = true
  await page.goto('/')
  await expect(page.getByRole('button', { name: '重新加载会话', exact: true })).toBeVisible()
  await composer(page).fill('新问题')
  await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeDisabled()
  backend.listFailure = false
  backend.historyFailure = true
  await page.getByRole('button', { name: '重新加载会话', exact: true }).click()
  await expect(page.getByRole('button', { name: '重新加载聊天记录', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeDisabled()
  backend.historyFailure = false
  await page.getByRole('button', { name: '重新加载聊天记录', exact: true }).click()
  await expect(page.getByText('历史回复')).toBeVisible()
})

test('late history response does not overwrite the subsequently selected conversation', async ({ page }) => {
  const backend = await mockBackend(page, [{ id: 'a', title: '会话 A' }, { id: 'b', title: '会话 B' }])
  let release
  const waiting = new Promise((resolve) => { release = resolve })
  backend.onHistory = async (route, id) => {
    if (id === 'a') await waiting
    return route.fulfill({ json: success(history(`问题 ${id}`, `回复 ${id}`)) })
  }
  await page.goto('/')
  await expect(page.getByText('正在加载聊天记录…')).toBeVisible()
  await page.getByRole('button', { name: '会话 B', exact: true }).click()
  await expect(page.getByText('回复 b')).toBeVisible()
  release()
  await expect(page.getByText('回复 a')).toHaveCount(0)
  await expect(page.getByText('回复 b')).toBeVisible()
})

test('desktop and mobile layouts fit, show history actions and have no runtime errors', async ({ page }) => {
  await mockBackend(page)
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.goto('/')
  await expect(page.locator('.example-card')).toHaveCount(3)
  await page.screenshot({ path: 'test-results/desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '打开侧栏' }).click()
  await expect(page.getByRole('textbox', { name: '搜索历史对话' })).toBeVisible()
  await page.screenshot({ path: 'test-results/mobile.png', fullPage: true, animations: 'disabled' })
  await page.locator('.mobile-close').click()
  await expect(page.getByRole('textbox', { name: '搜索历史对话' })).not.toBeVisible()
  expect(errors).toEqual([])
})

test('parses fragmented UTF-8, multiline data, CRLF, comments and multiple frames', () => {
  const events = []
  const parse = createSseParser((event) => events.push(event))
  const decoder = new TextDecoder()
  const bytes = new TextEncoder().encode('\ufeff:心跳\r\nid:1\r\nevent:message\r\ndata:第1步的结果为: 深圳\r\ndata:下一行\r\n\r\ndata:第二条\n\nevent:error\rdata:错误\r\rdata:未完成帧')
  for (const byte of bytes) parse(decoder.decode(Uint8Array.of(byte), { stream: true }))
  parse(decoder.decode())
  expect(events).toEqual([{ event: 'message', data: '第1步的结果为: 深圳\n下一行' }, { event: 'message', data: '第二条' }, { event: 'error', data: '错误' }])
})

test('renders real network events progressively, closes on final event and restores steps', async ({ page }) => {
  const backend = await mockBackend(page)
  let response
  let request
  let closed = false
  const server = createServer((req, res) => {
    response = res
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Access-Control-Allow-Origin': '*' })
    res.flushHeaders()
    res.on('close', () => { closed = true })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  backend.onStream = (route) => {
    request = route.request()
    return route.continue({ url: `http://127.0.0.1:${server.address().port}/stream${new URL(request.url()).search}` })
  }
  try {
    await page.goto('/')
    await send(page, '深圳深大到北站')
    await expect.poll(() => Boolean(response)).toBe(true)
    const first = Buffer.from(sse('第1步的结果为: 已确认深圳起终点。'))
    response.write(first.subarray(0, 7))
    await expect(page.locator('.planning-step')).toHaveCount(0)
    response.write(first.subarray(7))
    await expect(page.getByText('已确认深圳起终点。')).toBeVisible()
    response.write(sse('第2步的结果为: **已查询换乘方案。**\n\n正在比较路线。'))
    await expect(page.locator('.planning-step')).toHaveCount(2)
    await page.screenshot({ path: 'test-results/streaming.png', fullPage: true })
    const answer = '## 推荐乘车路线\n\n最终路线内容。'
    backend.saveReply(request, answer, [{ number: 1, content: '已确认深圳起终点。' }])
    response.write(sse(`模型最终的输出为：${answer}`))
    await expect(page.getByRole('heading', { name: '推荐乘车路线' })).toBeVisible()
    await expect(page.getByRole('button', { name: '正在等待回复' })).toHaveCount(0)
    await expect.poll(() => closed).toBe(true)
    await page.reload()
    await page.locator('.planning-steps summary').click()
    await expect(page.getByText('已确认深圳起终点。')).toBeVisible()
    await expect(page.getByRole('heading', { name: '推荐乘车路线' })).toBeVisible()
  } finally {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
})

test('retains partial results after early EOF and retries', async ({ page }) => {
  const backend = await mockBackend(page)
  let requests = 0
  backend.onStream = (route, complete) => ++requests === 1
    ? route.fulfill({ contentType: 'text/event-stream', body: sse('第1步的结果为: 部分规划内容') }) : complete('重试后的最终结果')
  await page.goto('/')
  await send(page, '去深圳北站')
  await expect(page.getByText('部分规划内容')).toBeVisible()
  await expect(page.getByText(/连接已中断，尚未收到最终结果/)).toBeVisible()
  await page.getByRole('button', { name: '重新尝试', exact: true }).click()
  await expect(page.getByText('重试后的最终结果')).toBeVisible()
  await expect(page.locator('.user-bubble')).toHaveCount(1)
})

test('rejects non-SSE responses', async ({ page }) => {
  const backend = await mockBackend(page)
  backend.onStream = (route) => route.fulfill({ contentType: 'text/html', body: '<html>proxy fallback</html>' })
  await page.goto('/')
  await send(page, '去机场')
  await expect(page.getByText('服务未返回 SSE 流，请确认后端流式接口已启动。')).toBeVisible()
})
