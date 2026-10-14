# 微信云托管部署

在微信云托管中选择源码构建，构建目录设为项目根目录，Dockerfile 路径为 `Dockerfile`，服务监听端口设为 **8888**。
端口必须与容器内 Spring Boot 的监听端口一致，参见[官方 Java 部署说明](https://cloud.tencent.com/document/product/1243/49239)。

镜像分阶段构建 Maven 项目，运行环境包含 Java 21、Node.js 22 和高德 MCP 服务。
云端自动启用 `cloud` 配置，接口前缀仍为 `/api`，例如 `/api/agent/chat/stream`。

## 环境变量

在云托管服务的环境变量中配置以下值，构建镜像时不需要提供密钥：

| 变量 | 内容 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 百炼 API Key |
| `DASHSCOPE_BASE_URL` | 与本地运行时相同的 DashScope Agent 服务地址 |
| `AMAP_MAPS_API_KEY` | 高德地图 API Key |
| `SEARCH_API_KEY` | SearchApi.io API Key |

`SPRING_PROFILES_ACTIVE=cloud`、`SERVER_ADDRESS=0.0.0.0` 和 `SERVER_PORT=8888` 已在镜像中设置。
如果覆盖 `SERVER_PORT`，需要同步修改云托管服务端口。
如需调整 JVM 参数，通过 `JAVA_TOOL_OPTIONS` 设置，例如 `-XX:MaxRAMPercentage=60.0`；为 Node.js 子进程和 JVM 非堆内存保留空间。

## 本地验证

安装并启动 Docker 后，在项目根目录执行：

```powershell
docker build -t urban-travel-planning-agent .
docker run --rm -p 8888:8888 --env-file .env urban-travel-planning-agent
```

本地 `.env` 使用 `变量名=值` 的格式，填写上表中的四项配置；该文件已被 `.dockerignore` 排除，请勿提交密钥到代码仓库。
启动后访问 `http://localhost:8888/api/swagger-ui.html` 检查服务。
应用需能访问百炼、高德和 SearchApi.io；对话请求会调用外部服务。

构建时跳过依赖外部服务的 Spring 上下文测试，但仍编译测试代码。
Maven 和 npm 依赖在构建阶段下载，高德 MCP 在启动时直接由 `node` 运行，无需临时下载 npm 包。

## 流式接口超时排查

先请求 `/api/v3/api-docs` 确认服务可达，再使用支持 SSE 的前端或 `curl.exe -N` 检查逐步返回的数据。
控制台调试页面显示超时不一定代表后台没有执行：模型推理和工具调用可能需要几十秒才完成一步。

```powershell
curl.exe -N --max-time 310 --get --data-urlencode "message=我想从深大到深圳湾口岸" "https://你的服务域名/api/agent/chat/stream"
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

`-DskipTests` 与 Dockerfile 一致：仍编译测试代码，跳过需要外部 API 凭据及 MCP 服务的上下文测试。

如果更换镜像后云端仍出现 TLS 错误，需要检查云构建环境到该仓库的出口网络、代理或 CA 信任链。公司代理替换 TLS 证书时应按平台要求安装可信 CA；不要关闭证书校验或改用明文 HTTP。更换镜像是针对仓库访问路径的修复，最终结果仍需由云端重新构建确认。
