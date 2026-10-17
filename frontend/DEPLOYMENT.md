# 前端部署到微信云托管

前端作为独立服务部署：Node.js 22 构建 Vue/Vite，Nginx 在容器的 **80** 端口提供静态页面，并将 `/api/` 请求代理到已部署的 Spring Boot 后端。浏览器始终访问前端同源地址，无需为此修改后端 CORS。

## 云托管配置

在现有环境中新建前端服务（例如 `route-pilot-web`），通过源码构建部署，填写：

| 配置项 | 值 |
| --- | --- |
| 构建目录 / 目标目录 | `frontend` |
| Dockerfile 路径（相对于构建目录） | `Dockerfile` |
| 服务监听端口 | `80` |
| 启动命令 | 留空，使用镜像默认命令 |
| 运行时环境变量 | `BACKEND_ORIGIN=https://你的后端服务域名` |
| HTTP 健康检查（如启用） | 端口 `80`，路径 `/healthz` |

如果控制台的 Dockerfile 路径明确要求相对于**仓库根目录**，填写 `frontend/Dockerfile`，但构建上下文仍必须是 `frontend`。本地上传时，选择 `frontend` 文件夹作为上传根目录。仓库根目录的 Dockerfile 用于后端，且根目录 `.dockerignore` 排除了 `frontend`，不能用根目录作为此前端镜像的构建上下文。

开启前端服务的公网访问，部署成功后使用控制台提供的 HTTPS 域名访问页面；容器内使用 HTTP 80，由平台入口处理 HTTPS。

## 后端地址

`BACKEND_ORIGIN` 是**运行时环境变量**，在前端服务的版本配置中填写。例如后端接口为：

```text
https://your-backend.example.com/api/chat/stream
```

则填写：

```text
BACKEND_ORIGIN=https://your-backend.example.com
```

不要附加 `/api`、其他路径或末尾 `/`。启动脚本会检查格式，缺失或格式不正确时退出，并在容器日志中提示原因。后端域名在 Nginx 启动时解析，需确保容器能解析并访问该地址；更换地址或域名解析目标后需重启/重新发布前端版本。

使用后端公网域名时，后端需要允许来自前端容器的 HTTP 请求，不能仅允许小程序 `callContainer` 调用。如已有平台提供的、从前端容器可达的内网地址，也可使用 `http://实际内网主机:端口`；内网直连 Spring Boot 时，端口通常是现有配置的 `8888`，以控制台实际访问方式为准。

镜像构建固定 `VITE_API_BASE_URL=/api`。`API_PROXY_TARGET` 仅控制本地 Vite 代理；在云托管运行时设置 `VITE_API_BASE_URL` 或 `API_PROXY_TARGET` 不会改变已构建页面的请求地址。云端只需设置 `BACKEND_ORIGIN`，不需要将后端 API Key 或 `JWT_SECRET` 配到前端服务。

Nginx 会把浏览器的 `Authorization` 原样转给后端。登录、注册以及带令牌的会话请求都走 `/api/`，不需要单独的代理规则。

## 本地验证

安装并启动 Docker 后，在**仓库根目录**执行（将示例后端地址换为真实地址）：

```powershell
docker build -t route-pilot-web ./frontend
docker run --rm -p 8080:80 -e BACKEND_ORIGIN=https://your-backend.example.com route-pilot-web
```

若连接电脑上运行的 Spring Boot，在 Docker Desktop 中可将变量设为 `BACKEND_ORIGIN=http://host.docker.internal:8888`。

另开终端验证：

```powershell
curl.exe -i http://localhost:8080/healthz
curl.exe -i http://localhost:8080/api/health
curl.exe -i http://localhost:8080/api/chat
curl.exe -i -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" -d "{\"username\":\"demo_user\",\"password\":\"demo-pass\"}"
```

页面地址为 `http://localhost:8080`。`/healthz` 由前端 Nginx 返回 `ok`，不依赖后端。`/api/health` 应返回后端的 `ok`。未登录时 `/api/chat` 应返回 HTTP 401。登录成功后，用返回的 token 再请求：

```powershell
curl.exe -i http://localhost:8080/api/chat -H "Authorization: Bearer 登录返回的token"
curl.exe -N --max-time 310 --get --data-urlencode "message=我想从深大到深圳湾口岸" -H "Authorization: Bearer 登录返回的token" http://localhost:8080/api/chat/stream
```

带令牌的 `/api/chat` 应返回 `code=200` 的当前用户会话列表，聊天应逐步输出 SSE 数据。最后一个请求会实际调用后端智能体。云端部署后，将以上地址替换为前端 HTTPS 域名做同样验证。

会话接口已统一到 `/api/chat`：列表用 GET，改标题用 PUT，删除用 DELETE；历史消息使用 `/api/chat/messages`，持续对话使用 `/api/chat/stream`。现有 Nginx 配置代理整个 `/api/` 并保留请求方法、路径和查询参数，因此无需为这些接口新增代理规则。
前端从后端加载历史记录，不再用浏览器 localStorage 保存会话。发布前先按[后端部署文档](../DEPLOYMENT.md)配置 MySQL 并建表，再验证发送后刷新页面可以恢复历史、继续对话沿用同一个 `chatId`、修改标题和删除会话在刷新后仍然生效。

## 流式响应与排查

Nginx 已关闭响应缓冲、缓存、压缩和自动重试，代理读取超时为 330 秒，配合前端 310 秒、后端 300 秒的总超时。Nginx 读取超时是两次读取之间的等待时间，不能覆盖云托管入口或后端网关自身的总请求时限；后端心跳也不能延长平台总时限。若平台提供请求超时配置，应结合上述时长设置，并通过实际域名验证 SSE。

- 页面正常、接口 502：检查 `BACKEND_ORIGIN`、后端访问权限、网络及容器日志中的 DNS/TLS 错误。
- 接口 404：确认后端接口包含 `/api`，且环境变量没有重复添加该前缀。
- 接口 401：未登录访问会话接口是预期结果。登录后的请求必须带 `Authorization: Bearer`。
- 页面返回 403 或鉴权提示：检查所访问服务的公网访问设置。
- 消息一次性出现或固定时长断开：分别直连后端和经过前端代理执行 `curl.exe -N`，定位后端、代理与平台入口的差异。

配置参考：[微信云托管官方 Nginx/HTML 示例](https://github.com/WeixinCloud/DockerTemplate)、[Nginx 官方镜像模板机制](https://hub.docker.com/_/nginx)、[Nginx 代理配置文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。
