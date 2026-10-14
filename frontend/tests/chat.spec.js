import { test, expect } from '@playwright/test'
import { createServer } from 'node:http'
import { createSseParser } from '../src/api/sse.js'

const sse = (text) => `${text.split('\n').map((line) => `data:${line}`).join('\n')}\n\n`
const finalReply = (text) => ({ contentType: 'text/event-stream', body: sse(`模型最终的输出为：${text}`) })

test('sends a GET with the selected preference and safely renders the reply', async ({ page }) => {
  let request
  await page.route('**/api/agent/chat/stream?**', async (route) => {
    request = route.request()
    await route.fulfill(finalReply('## 推荐路线\n\n请乘坐 **地铁**。\n\n<script>window.injected = true</script><img src=x onerror="window.injected = true">'))
  })
  await page.goto('/')
  await page.getByRole('button', { name: /从深大到深圳北站/ }).click()
  await page.getByRole('button', { name: '少换乘', exact: true }).click()
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByRole('heading', { name: '推荐路线' })).toBeVisible()
  expect(request.method()).toBe('GET')
  expect(new URL(request.url()).searchParams.get('message')).toBe('在深圳，从深大地铁站到深圳北站怎么坐地铁？\n出行偏好：少换乘。')
  expect(await page.evaluate(() => window.injected)).toBeUndefined()
  await expect(page.locator('.markdown img, .markdown script')).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('heading', { name: '推荐路线' })).toBeVisible()
})

test('prevents duplicate submissions while pending, then retries a failed request', async ({ page }) => {
  let requests = 0
  let release
  const waiting = new Promise((resolve) => { release = resolve })
  await page.route('**/api/agent/chat/stream?**', async (route) => {
    requests++
    if (requests === 1) {
      await waiting
      await route.fulfill({ status: 503, body: 'Unavailable' })
    } else {
      await route.fulfill(finalReply('已重新规划路线。'))
    }
  })
  await page.goto('/')
  const input = page.getByRole('textbox', { name: '描述你的地铁出行需求' })
  await input.fill('深圳深大到深圳北站')
  await input.press('Enter')
  await expect(page.getByText('正在为你规划路线…')).toBeVisible()
  await input.fill('不能重复提交')
  await input.press('Enter')
  await expect(page.getByRole('button', { name: '正在等待回复' })).toBeDisabled()
  release()
  await expect(page.getByText(/暂时无法连接出行助手/)).toBeVisible()
  expect(requests).toBe(1)
  await page.getByRole('button', { name: '重新尝试' }).click()
  await expect(page.getByText('已重新规划路线。')).toBeVisible()
  await expect(page.locator('.user-bubble')).toHaveCount(1)
  expect(requests).toBe(2)
})

test('handles empty replies and keeps punctuation and newlines in query parameters', async ({ page }) => {
  let query
  await page.route('**/api/agent/chat/stream?**', async (route) => {
    query = new URL(route.request().url()).searchParams.get('message')
    await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' })
  })
  await page.goto('/')
  const input = page.getByRole('textbox', { name: '描述你的地铁出行需求' })
  await input.fill('深圳 A&B + 北站？')
  await input.press('Shift+Enter')
  await input.press('a')
  await input.press('Enter')
  await expect(page.getByText('智能体暂时没有返回内容，请重试。')).toBeVisible()
  expect(query).toBe('深圳 A&B + 北站？\na')
})

test('stores, searches, switches and deletes local conversations', async ({ page }) => {
  await page.route('**/api/agent/chat/stream?**', (route) => route.fulfill(finalReply('路线规划结果')))
  await page.goto('/')
  await page.getByRole('textbox', { name: '描述你的地铁出行需求' }).fill('深圳北站怎么走')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByText('路线规划结果')).toBeVisible()
  await page.getByRole('button', { name: /开启新对话/ }).click()
  await expect(page.getByRole('heading', { name: '下一站，想去哪里？' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('不存在')
  await expect(page.getByText('没有找到相关对话')).toBeVisible()
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('深圳')
  await page.getByRole('button', { name: '深圳北站怎么走', exact: true }).click()
  await expect(page.getByText('路线规划结果')).toBeVisible()
  await page.getByRole('button', { name: '删除对话：深圳北站怎么走' }).click()
  await expect(page.getByRole('heading', { name: '下一站，想去哪里？' })).toBeVisible()
  await page.reload()
  await expect(page.getByText('每一次出发，都从这里开始')).toBeVisible()
})

test('mobile layout fits the viewport and the history drawer works', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '下一站，想去哪里？' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '打开侧栏' }).click()
  await expect(page.getByRole('textbox', { name: '搜索历史对话' })).toBeVisible()
  await page.locator('.mobile-close').click()
  await expect(page.getByRole('textbox', { name: '搜索历史对话' })).not.toBeVisible()
  await page.screenshot({ path: 'test-results/mobile.png', fullPage: true })
})

test('desktop welcome page renders without runtime errors', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.goto('/')
  await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeDisabled()
  await expect(page.locator('.example-card')).toHaveCount(3)
  await page.screenshot({ path: 'test-results/desktop.png', fullPage: true })
  expect(errors).toEqual([])
})

test('parses fragmented UTF-8, multiline data, CRLF, comments and multiple frames', () => {
  const events = []
  const parse = createSseParser((event) => events.push(event))
  const decoder = new TextDecoder()
  const bytes = new TextEncoder().encode('\ufeff:心跳\r\nid:1\r\nevent:message\r\ndata:第1步的结果为: 深圳\r\ndata:下一行\r\n\r\ndata:第二条\n\nevent:error\rdata:错误\r\rdata:未完成帧')
  for (const byte of bytes) parse(decoder.decode(Uint8Array.of(byte), { stream: true }))
  parse(decoder.decode())
  expect(events).toEqual([
    { event: 'message', data: '第1步的结果为: 深圳\n下一行' },
    { event: 'message', data: '第二条' },
    { event: 'error', data: '错误' },
  ])
})

test('renders each real network event before final output and closes an unfinished HTTP response', async ({ page }) => {
  let response
  let requests = 0
  let closed = false
  const server = createServer((req, res) => {
    requests++
    response = res
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Access-Control-Allow-Origin': '*' })
    res.flushHeaders()
    res.on('close', () => { closed = true })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    await page.route('**/api/agent/chat/stream?**', (route) => route.continue({
      url: `http://127.0.0.1:${server.address().port}/stream${new URL(route.request().url()).search}`,
    }))
    await page.goto('/')
    await page.getByRole('textbox', { name: '描述你的地铁出行需求' }).fill('深圳深大到北站')
    await page.getByRole('button', { name: '发送消息', exact: true }).click()
    await expect.poll(() => Boolean(response)).toBe(true)
    const first = Buffer.from(sse('第1步的结果为: 已确认深圳起终点。'))
    response.write(first.subarray(0, 7)) // Deliberately split a Chinese UTF-8 character.
    await expect(page.locator('.planning-step')).toHaveCount(0)
    response.write(first.subarray(7))
    await expect(page.getByText('已确认深圳起终点。')).toBeVisible()
    await expect(page.getByRole('button', { name: '正在等待回复' })).toBeDisabled()
    response.write(sse('第2步的结果为: **已查询换乘方案。**\n\n正在比较路线。'))
    await expect(page.locator('.planning-step')).toHaveCount(2)
    await expect(page.getByText('已查询换乘方案。')).toBeVisible()
    await page.screenshot({ path: 'test-results/streaming.png', fullPage: true })
    response.write(sse('模型最终的输出为：## 推荐乘车路线\n\n最终路线内容。'))
    // The backend currently leaves the HTTP response open after its final event.
    await expect(page.getByRole('heading', { name: '推荐乘车路线' })).toBeVisible()
    await expect(page.getByRole('button', { name: '正在等待回复' })).toHaveCount(0)
    await expect.poll(() => closed).toBe(true)
    expect(requests).toBe(1)
    await page.reload()
    await page.locator('.planning-steps summary').click()
    await expect(page.getByText('已确认深圳起终点。')).toBeVisible()
    await expect(page.getByRole('heading', { name: '推荐乘车路线' })).toBeVisible()
  } finally {
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
})

test('retains partial results on early EOF and retries without duplicating the user message', async ({ page }) => {
  let requests = 0
  await page.route('**/api/agent/chat/stream?**', (route) => {
    requests++
    return route.fulfill(requests === 1
      ? { contentType: 'text/event-stream', body: sse('第1步的结果为: 部分规划内容') }
      : finalReply('重试后的最终结果'))
  })
  await page.goto('/')
  await page.getByRole('textbox', { name: '描述你的地铁出行需求' }).fill('去深圳北站')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByText('部分规划内容')).toBeVisible()
  await expect(page.getByText(/连接已中断，尚未收到最终结果/)).toBeVisible()
  await page.getByRole('button', { name: '重新尝试' }).click()
  await expect(page.getByText('重试后的最终结果')).toBeVisible()
  await expect(page.locator('.user-bubble')).toHaveCount(1)
  expect(requests).toBe(2)
})

test('restores interrupted streams as retryable messages after reload', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('routepilot.conversations.v1', JSON.stringify([
    { id: 'test', title: '上次规划', messages: [
      { id: 'u', role: 'user', content: '去深圳北站' },
      { id: 'a', role: 'assistant', content: '', status: 'streaming', request: '去深圳北站', steps: [{ number: 1, content: '已确认起点' }] },
    ] },
  ])))
  await page.goto('/')
  await expect(page.getByText('已确认起点')).toBeVisible()
  await expect(page.getByText(/上次规划因页面关闭而中断/)).toBeVisible()
  await expect(page.getByRole('button', { name: '重新尝试' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '正在等待回复' })).toHaveCount(0)
})

test('rejects a non-SSE response instead of showing HTML as a route', async ({ page }) => {
  await page.route('**/api/agent/chat/stream?**', (route) => route.fulfill({ contentType: 'text/html', body: '<html>proxy fallback</html>' }))
  await page.goto('/')
  await page.getByRole('textbox', { name: '描述你的地铁出行需求' }).fill('去机场')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByText('服务未返回 SSE 流，请确认后端流式接口已启动。')).toBeVisible()
})
