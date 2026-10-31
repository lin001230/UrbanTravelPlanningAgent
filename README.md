# 路小行 · RoutePilot

深圳地铁出行规划智能体。用户登录后用自然语言描述行程，服务会压缩早期对话、重写当前问题，再检索地铁知识库，并调用腾讯位置服务解析地点和查询实时路线。

## 组成

| 部分 | 技术 | 说明 |
| --- | --- | --- |
| 后端 | Java 21、Spring Boot 3.5、MyBatis | 端口 `8888`，接口前缀 `/api` |
| 前端 | Vue 3、Vite | 开发地址 `http://localhost:5173`，把 `/api` 代理到后端 |
| 数据库 | MySQL 5.7.8+ / 8.x | 用户、会话、消息，以及知识库向量 |
| 模型 | 通义千问 `qwen3.8-flash` | 嵌入模型是 `text-embedding-v4`，1024 维 |

聊天模型负责规划和工具调用。知识库嵌入使用另一个模型，两边必须保持 `text-embedding-v4` 和 1024 维，否则相似度无法比较。

## 本地启动

后端需要 JDK 21、Maven，以及一个可连接的 MySQL。前端需要 Node.js 20.19+ 或 22.12+。

把根目录 [`.env.example`](.env.example) 里的变量配到当前进程，不要把真实密钥提交进仓库：

| 变量 | 用途 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 百炼对话和向量化 |
| `DASHSCOPE_BASE_URL` | DashScope 服务地址 |
| `TENCENT_MAP_API_KEY` | 腾讯位置服务 WebService Key，用于地址解析和公交地铁规划 |
| `DEEPSEEK_SEARCH_API_KEY` | DeepSeek 网页搜索 |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | 数据库地址，本地默认库名 `urban_travel_planning_agent` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库账号 |
| `JWT_SECRET` | 登录令牌签名，正式环境换成足够长的随机字符串 |

空库先执行 [`src/main/resources/schema.sql`](src/main/resources/schema.sql)。这个脚本会删除并重建 `app_user`、`chat_session`、`chat_message`。已有数据的库不要重复执行。

知识库表单独执行 [`src/main/resources/knowledge.sql`](src/main/resources/knowledge.sql)，它不会改动上面三张表。

启动后端（默认 `local` 配置）：

```powershell
mvn spring-boot:run
```

健康检查是 `http://localhost:8888/api/health`，应返回纯文本 `ok`。接口文档在 `http://localhost:8888/api/swagger-ui.html`。

启动前端：

```powershell
cd frontend
npm install
npm run dev
```

前端说明见 [`frontend/README.md`](frontend/README.md)。

## 知识库

稳定资料放在知识库里：站点介绍、出入口换乘、相邻站、首末班车。实时路线由腾讯位置服务查询，不写入向量。

原始表和生成结果不进入 Git。把四张源表放到 `knowledge/source/` 后：

```powershell
python knowledge/build/dedupe.py
```

去重结果在 `knowledge/output/`。然后在 IDE 中运行：

1. `KnowledgeEmbedCommand`：把去重后的行写成句子，调用 `text-embedding-v4`，追加写入 `knowledge/output/embeddings.jsonl`。
2. `KnowledgeChunkImportCommand`：把 jsonl 写入 MySQL 的 `knowledge_chunk`。同一条 `id` 再次导入会覆盖旧向量。

对话时，`KnowledgeRetriever` 把重写后的问题变成向量，和表中向量计算余弦相似度，取最相关的几句放进当次提示词。没有命中时不加入这段资料。

要带到云端数据库时，生成一份建表加数据的脚本：

```powershell
python knowledge/build/export_knowledge_sql.py
```

在云端控制台选中目标库后执行 `knowledge/output/knowledge_chunk.sql`。该脚本只重建 `knowledge_chunk`。

## 部署

微信云托管的构建目录是仓库根目录，Dockerfile 路径是 `Dockerfile`，服务端口是 **8888**。镜像不包含测试代码和知识库文件。

环境变量、健康检查和本地 Docker 运行方式见 [`DEPLOYMENT.md`](DEPLOYMENT.md)。前端镜像见 [`frontend/DEPLOYMENT.md`](frontend/DEPLOYMENT.md)。
