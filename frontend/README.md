# 路小行 · RoutePilot

Vue 3 + Vite 单页地铁路线规划聊天应用，使用 axios 调用现有 Spring Boot 接口。

## 本地运行

需要 Node.js 20.19+ 或 22.12+。

```powershell
cd D:\demo\Idea\urban-travel-planning-agent\frontend
npm install
npm run dev
```

打开终端显示的地址（默认 http://localhost:5173）。后端需独立启动在 http://localhost:8888。

浏览器发送 `GET /api/chat/stream?message=...`，Vite 将 `/api` 原样代理到 `http://localhost:8888`，无需修改后端 CORS。axios 使用 `fetch` adapter 和 `responseType: 'stream'` 实时读取 SSE，使用 `params` 编码中文及特殊字符；不自动重连或重试，避免重复触发智能体。前端总等待上限为 310 秒，代理超时为 330 秒，与后端 300 秒的 SSE 超时配合。

如需修改地址，将 `.env.example` 复制为 `.env.local` 后调整，并重启开发服务。

如果 `/api/chat/stream` 返回 404，请确认已重新启动最新的 Spring Boot 代码。可以查看 `http://localhost:8888/api/v3/api-docs` 的 `paths` 是否包含 `/chat/stream`。修改代理配置后也需要重启 Vite。

## 功能

- 单页对话、逐步流式展示规划过程、最终结果单独呈现、Markdown 安全渲染、复制和失败重试。
- 规划过程中自动展开步骤，完成后折叠（可以再次展开）。向上阅读时不强制滚动到底部。
- 连接中断时在当前页面保留已收到的步骤，支持重新发起整次规划，并替换失败的助手回复。刷新页面后以服务端已保存的历史为准。
- Enter 发送，Shift + Enter 换行，兼容中文输入法；禁止重复提交。
- 示例问题填入输入框，可编辑后发送；所选出行偏好作为文字附加到请求中。
- 从服务端加载会话列表和历史消息，支持搜索、重命名、确认删除、新对话和加载失败重试。会话列表按最近活跃时间排列；浏览器本地存储不再作为历史来源。
- 桌面侧栏、手机抽屉、自适应输入框、返回最新消息。

## 接口边界

后端接收 `message`，通过 `SseEmitter.send(String)` 发送默认 SSE 消息，目前约定为：

```text
data:第1步的结果为: ...

data:第2步的结果为: ...

data:模型最终的输出为：...

```

前端处理分块 UTF-8、跨块事件、同块多事件、多行 `data:` 和 SSE 注释。收到“模型最终的输出为：”后立即结束读取并恢复发送按钮；未收到最终事件就断流视为失败，保留步骤供查看。步骤前缀和最终前缀是当前后端协议的一部分，修改后端文案时需同步调整 `src/api/sse.js`。

后端先保存本轮模型消息，再发送最终事件，并在 finally 中完成 SSE。最终事件表示回复已成功落库，前端随后刷新会话列表。

聊天请求携带 `chatId`，后端从数据库恢复该会话的模型上下文。页面历史使用独立 DTO，按回合展示用户原文、最终回答和可展开的执行步骤，不显示系统提示词。现有单例 Agent 同时处理一个规划请求，前端在规划期间禁止再次发送。

会话接口（均带 `/api` 前缀）：

| 方法 | 路径 | 参数 / 返回 |
| --- | --- | --- |
| GET | `/chat` | 返回最近活跃的会话列表 |
| GET | `/chat/messages` | `chatId`；返回页面历史消息 |
| PUT | `/chat` | `chatId`、`title`；标题为 1–64 个字符 |
| DELETE | `/chat` | `chatId`；删除会话和消息 |
| GET | `/chat/stream` | `chatId`、`message`；返回 SSE |

普通接口返回 `{ code, message, data }`，业务成功码为 `200`。前端同时检查 HTTP 状态和业务状态码。历史消息格式为 `{ id, turnId, role, content, steps: [{ number, content }] }`。

关闭页面会断开客户端连接，但不保证停止后端任务。网络错误、非 2xx、非 SSE 响应、超时、空回复、SSE `error` 事件及不完整结束均可手动重试。

## 构建与部署

部署到微信云托管请参阅 [前端部署说明](./DEPLOYMENT.md)：已提供多阶段 `Dockerfile`、Nginx SSE 代理配置和运行时后端地址校验。构建目录选择 `frontend`，服务端口为 `80`，通过 `BACKEND_ORIGIN` 指向已部署的后端。

```powershell
npm run build
npm run preview
```

`dist` 为静态构建产物。预览服务也配置了相同代理。正式部署需要将 `/api` 反向代理到后端，或将 `VITE_API_BASE_URL` 设为后端绝对 API 前缀并在后端配置相应 CORS；Vite 的代理不是生产服务。SSE 代理应关闭响应缓冲（例如 Nginx `proxy_buffering off`），读取超时设置为大于 300 秒，避免代理积攒内容或过早断开。

## 验证

```powershell
npx playwright install chromium
npm test
```

浏览器测试使用模拟 JSON/SSE 响应和临时本地 HTTP 流服务器，验证逐步到达、最终事件后主动断开、中文分块、多行事件、不完整结束、失败重试、GET 参数、安全渲染、服务端历史恢复、标题修改、删除失败、会话切换和移动端交互，不依赖真实大模型服务。

已有 Microsoft Edge 时，也可跳过 Chromium 下载，在 PowerShell 中运行 `$env:PLAYWRIGHT_CHANNEL='msedge'; npm test`。
