# 微信云托管部署

在微信云托管中选择源码构建，构建目录设为项目根目录，Dockerfile 路径为 `Dockerfile`，服务监听端口设为 **8888**。
端口必须与容器内 Spring Boot 的监听端口一致，参见[官方 Java 部署说明](https://cloud.tencent.com/document/product/1243/49239)。

镜像分阶段构建 Maven 项目，运行环境包含 Java 21、Node.js 22 和高德 MCP 服务。
云端自动启用 `cloud` 配置，接口前缀仍为 `/api`，聊天接口为 `/api/chat/stream`。
会话和消息保存在外部 MySQL 中，容器重启或重新部署后仍可读取，无需给后端容器挂载聊天数据目录。

## 环境变量

在云托管服务的环境变量中配置以下值，构建镜像时不需要提供密钥：

| 变量 | 内容 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 百炼 API Key |
| `DASHSCOPE_BASE_URL` | 与本地运行时相同的 DashScope Agent 服务地址 |
| `AMAP_MAPS_API_KEY` | 高德地图 API Key |
| `SEARCH_API_KEY` | SearchApi.io API Key |
| `MYSQL_HOST` | 后端容器能够访问的 MySQL 主机地址，不包含协议和端口，不能使用 `localhost` |
| `MYSQL_PORT` | MySQL 端口，默认 `3306` |
| `MYSQL_DATABASE` | 已创建的数据库名称，默认 `urban_travel_planning_agent` |
| `MYSQL_USERNAME` | 具有会话、消息表读写权限的数据库用户名 |
| `MYSQL_PASSWORD` | 数据库密码 |
| `JWT_SECRET` | 登录令牌签名密钥。不配置时使用镜像内置默认值，进程可以启动；正式环境应换成足够长的随机字符串 |

`JWT_SECRET` 只在运行时读取，不要写进镜像。令牌有效期仍为 2 小时。

`application-cloud.yml` 使用这些变量生成 JDBC URL，不再连接容器内的 `localhost:3306`。
数据库的连接信息只配置到后端服务；前端仍只需要 `BACKEND_ORIGIN`。

`SPRING_PROFILES_ACTIVE=cloud`、`SERVER_ADDRESS=0.0.0.0` 和 `SERVER_PORT=8888` 已在镜像中设置。
如果覆盖 `SERVER_PORT`，需要同步修改云托管服务端口。
如需调整 JVM 参数，通过 `JAVA_TOOL_OPTIONS` 设置，例如 `-XX:MaxRAMPercentage=60.0`；为 Node.js 子进程和 JVM 非堆内存保留空间。

## 数据库准备

新的空库使用具备建库、建表权限的账号执行 [`src/main/resources/schema.sql`](src/main/resources/schema.sql)。脚本面向 MySQL 5.7.8+ / 8.x，会删除并重建 `app_user`、`chat_session`、`chat_message` 三张空表，不插入初始账号或消息。已有云端库不要执行这个脚本，否则会话和消息会被清空。

当前代码在注册、登录和新建会话时会访问 `app_user`，并写入 `chat_session.user_id`。已有云端库在发布新版本前执行一次 [`src/main/resources/migrations/001_add_app_user_and_session_owner.sql`](src/main/resources/migrations/001_add_app_user_and_session_owner.sql)：新增用户表，并为会话表补上可空的 `user_id`。已有会话保留，但没有所有者，不会出现在登录后的会话列表中。该脚本里的 `ALTER TABLE` 只执行一次；列或索引已存在时删掉对应语句再执行。

云端已显式设置 `spring.sql.init.mode=never`，启动应用不会自动建表或修改表结构，镜像构建也不需要连接数据库。MySQL 驱动版本需要与实际服务端版本匹配。

确保云托管后端容器能够连接该 MySQL 地址和端口，并配置好数据库侧允许的访问来源。`localhost` 指向后端容器自身，不能用于连接外部 MySQL。

## 本地验证

安装并启动 Docker 后，在项目根目录执行：

```powershell
Copy-Item .env.example .env
# 编辑 .env，填写真实的 API 和数据库连接信息，再执行以下命令。
docker build -t urban-travel-planning-agent .
docker run --rm -p 8888:8888 --env-file .env urban-travel-planning-agent
```

本地 `.env` 使用 `变量名=值` 的格式，参考根目录 `.env.example` 填写 API 和数据库连接信息；该文件已被 `.gitignore` 和 `.dockerignore` 排除。
在 Docker Desktop 中连接电脑上运行的 MySQL 时，可以使用 `MYSQL_HOST=host.docker.internal`；云端应替换为实际可达的数据库地址。
`.env` 由 `docker run --env-file` 注入；直接使用 Maven 或 IDE 启动时，仍需在进程环境变量中配置相应的值。
启动后访问 `http://localhost:8888/api/health`，应返回纯文本 `ok`。这个地址不访问数据库，也不要求登录，可作为云托管 HTTP 健康检查路径；检查端口仍是 **8888**。接口文档在 `http://localhost:8888/api/swagger-ui.html`。
应用需能访问百炼、高德和 SearchApi.io；对话请求会调用外部服务。

构建时只打包应用，不运行测试。`src/test` 和 `knowledge/` 不会进入镜像。知识库先在云端 MySQL 中执行 `knowledge/output/knowledge_chunk.sql`，服务通过 `MYSQL_*` 环境变量读取 `knowledge_chunk`。
Maven 和 npm 依赖在构建阶段下载，高德 MCP 在启动时直接由 `node` 运行，无需临时下载 npm 包。

## 会话接口验证

后端部署后，先确认进程已监听，再验证登录。未带令牌访问会话列表应返回 HTTP 401。

```powershell
curl.exe -i http://localhost:8888/api/health
curl.exe -i http://localhost:8888/api/chat
curl.exe -i -X POST http://localhost:8888/api/user/register -H "Content-Type: application/json" -d "{\"username\":\"demo_user\",\"password\":\"demo-pass\",\"nickname\":\"demo\"}"
curl.exe -i -X POST http://localhost:8888/api/user/login -H "Content-Type: application/json" -d "{\"username\":\"demo_user\",\"password\":\"demo-pass\"}"
```

`/api/health` 返回 `ok`。`/api/chat` 返回 HTTP 401，JSON 中 `code=401`。注册和登录在数据库升级完成后返回 `code=200`；登录结果里的 `data.token` 用于后续请求：

```powershell
curl.exe -i http://localhost:8888/api/chat -H "Authorization: Bearer 登录返回的token"
```

应返回 HTTP 200，JSON 中 `code=200`，`data` 为当前用户的会话列表，没有会话时为 `[]`。该请求会实际访问数据库。
然后按 [`frontend/DEPLOYMENT.md`](frontend/DEPLOYMENT.md) 部署前端，验证注册登录、发送消息、刷新恢复历史、继续对话、修改标题和删除会话。
当前接口包括：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 存活检查，无需登录 |
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录并返回令牌 |
| GET | `/api/chat` | 查询当前用户的会话列表，需要 `Authorization: Bearer` |
| GET | `/api/chat/messages?chatId=...` | 查询会话历史 |
| PUT | `/api/chat?chatId=...&title=...` | 修改标题 |
| DELETE | `/api/chat?chatId=...` | 删除会话及其消息 |
| GET | `/api/chat/stream?chatId=...&message=...` | 新建或继续对话，返回 SSE |

## 流式接口超时排查

先请求 `/api/v3/api-docs` 确认服务可达，再使用支持 SSE 的前端或 `curl.exe -N` 检查逐步返回的数据。
控制台调试页面显示超时不一定代表后台没有执行：模型推理和工具调用可能需要几十秒才完成一步。

```powershell
curl.exe -N --max-time 310 --get --data-urlencode "message=我想从深大到深圳湾口岸" "https://你的服务域名/api/chat/stream"
```

流式接口不发送连接注释帧或定时心跳；查询重写和路线规划在后台执行，每完成一步才发送业务结果，异常时发送错误事件。
等待第一步结果期间可能没有响应数据；应用 SSE 总超时为 300 秒，调试工具或网关也可能有独立的超时限制。

如果工具返回 `CUQPS_HAS_EXCEEDED_THE_LIMIT`，检查高德控制台对应接口的调用频率限制，并减少重复调试和密集工具调用；增大 SSE 超时时间无法解决接口限流。

## Maven 下载阶段 TLS 握手失败

当 `dependency:go-offline` 报 `Remote host terminated the handshake` 或 `SSL peer shut down incorrectly` 时，表示构建容器访问仓库时 HTTPS 连接失败。这发生在代码编译之前；仅凭该日志不能归因于 Java 业务代码，也不能把 `absent` 当作依赖版本不存在。

Dockerfile 在解析 `pom.xml` 前复制 `.mvn/cloud-settings.xml`，两个 Maven 步骤都通过 `-s` 显式加载它，将 Maven Central 的依赖和插件请求转到阿里云 HTTPS 公共仓库。`-U` 使 Maven 重新检查先前缓存的下载失败。此设置仅用于显式指定它的构建，不会修改本机 Maven 全局配置。

将以下文件一起提交、推送到云托管关联的分支后，触发重新构建：

- `Dockerfile`
- `.mvn/cloud-settings.xml`

`.dockerignore` 必须允许 `.mvn/cloud-settings.xml` 进入构建上下文。当前配置已允许。Maven 日志中父 POM 下载仓库应显示为 `cloud-central (https://maven.aliyun.com/repository/public)`。

本地验证命令（需要 Java 21 和 Maven）：

```powershell
mvn -B -ntp -U -s .mvn/cloud-settings.xml dependency:go-offline
mvn -B -ntp -U -s .mvn/cloud-settings.xml -DskipTests package
```

打包方式和 Dockerfile 一致：不编译、不运行 `src/test`。

如果更换镜像后云端仍出现 TLS 错误，需要检查云构建环境到该仓库的出口网络、代理或 CA 信任链。公司代理替换 TLS 证书时应按平台要求安装可信 CA；不要关闭证书校验或改用明文 HTTP。更换镜像是针对仓库访问路径的修复，最终结果仍需由云端重新构建确认。
