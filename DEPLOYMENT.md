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
