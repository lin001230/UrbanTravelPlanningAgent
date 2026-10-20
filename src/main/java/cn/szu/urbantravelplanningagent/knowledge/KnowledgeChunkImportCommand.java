package cn.szu.urbantravelplanningagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 knowledge/output/embeddings.jsonl 中的向量写入 MySQL 的 knowledge_chunk。
 * 在 KnowledgeEmbedCommand 算完全部向量之后运行。
 * 同一条 id 再次导入时覆盖旧行。
 */
public class KnowledgeChunkImportCommand {

    /** 与 KnowledgeEmbedCommand 写出的向量维度一致。 */
    private static final int EMBEDDING_DIMENSION = 1024;

    /** 每批提交的行数。向量 JSON 较长，一批太大时单次请求会过重。 */
    private static final int BATCH_SIZE = 200;

    /**
     * id 是主键。再次导入同一条时覆盖文本和向量，避免表里留下旧模型的结果。
     * VALUES() 在 MySQL 5.7 和 8.x 都能用。
     */
    private static final String UPSERT_SQL = """
            INSERT INTO knowledge_chunk (id, source, text, meta, embedding)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                source = VALUES(source),
                text = VALUES(text),
                meta = VALUES(meta),
                embedding = VALUES(embedding)
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** load 读到的记录，留给 save 写入 MySQL。meta 和 embedding 保持 JSON 文本。 */
    private final List<ChunkRow> chunks = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path jsonlFile = findEmbeddingsFile();
        KnowledgeChunkImportCommand command = new KnowledgeChunkImportCommand();
        command.load(jsonlFile);
        System.out.println("loaded " + command.chunks.size());
        command.save();
    }

    /**
     * 从 jsonl 逐行读出 id、source、text、meta、embedding。
     * 空行跳过。缺字段或向量不是 1024 维时直接失败，避免把半截数据送进 save。
     */
    private void load(Path jsonlFile) throws IOException {
        // 清空 chunks 列表
        chunks.clear();
        // 检查文件是否存在
        if (!Files.exists(jsonlFile)) {
            throw new IllegalStateException("找不到向量文件：" + jsonlFile);
        }
        int lineNumber = 0;
        // 逐行读取文件
        for (String line : Files.readAllLines(jsonlFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            // 跳过空行
            if (line.isBlank()) {
                continue;
            }
            // 解析 JSON 行
            JsonNode node = JSON.readTree(line);
            // 获取 embedding 节点
            JsonNode embeddingNode = node.get("embedding");
            // 检查 embedding 维度
            if (embeddingNode == null || !embeddingNode.isArray() || embeddingNode.size() != EMBEDDING_DIMENSION) {
                int actual = embeddingNode != null && embeddingNode.isArray() ? embeddingNode.size() : 0;
                throw new IllegalStateException("第 " + lineNumber + " 行向量维度不是 " + EMBEDDING_DIMENSION + "，实际是 " + actual);
            }
            // 获取 meta 节点
            JsonNode metaNode = node.get("meta");
            // 添加到 chunks 列表
            chunks.add(new ChunkRow(
                    requiredText(node, "id", lineNumber),
                    requiredText(node, "source", lineNumber),
                    requiredText(node, "text", lineNumber),
                    metaNode == null || metaNode.isNull() ? null : metaNode.toString(),
                    embeddingNode.toString()
            ));
        }
    }

    /**
     * 把读到的记录写入 knowledge_chunk。
     * 列对应：id、source、text、meta、embedding。
     * 账号与 application.yml 相同，读取 MYSQL_USERNAME、MYSQL_PASSWORD。
     * 主机、端口、库名可用 MYSQL_HOST、MYSQL_PORT、MYSQL_DATABASE 覆盖，不设置时连本机库。
     */
    private void save() throws SQLException {
        if (chunks.isEmpty()) {
            throw new IllegalStateException("没有可写入的向量，请先调用 load");
        }
        String url = "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306")
                + "/" + env("MYSQL_DATABASE", "urban_travel_planning_agent")
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&rewriteBatchedStatements=true";
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("MYSQL_USERNAME"), requiredEnv("MYSQL_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            connection.setAutoCommit(false);
            int buffered = 0;
            for (ChunkRow chunk : chunks) {
                statement.setString(1, chunk.id());
                statement.setString(2, chunk.source());
                statement.setString(3, chunk.text());
                statement.setString(4, chunk.meta());
                statement.setString(5, chunk.embedding());
                statement.addBatch();
                buffered++;
                if (buffered == BATCH_SIZE) {
                    statement.executeBatch();
                    buffered = 0;
                }
            }
            if (buffered > 0) {
                statement.executeBatch();
            }
            connection.commit();
        }
        System.out.println("saved " + chunks.size());
    }

    /** 从当前目录向上找 embeddings.jsonl，这样在 IDEA 里不一定非要手动改工作目录。 */
    private static Path findEmbeddingsFile() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cursor != null; i++) {
            Path candidate = cursor.resolve("knowledge").resolve("output").resolve("embeddings.jsonl");
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到 knowledge/output/embeddings.jsonl，请先运行 KnowledgeEmbedCommand");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    /**
     * 获取 requiredText 节点
     * @param node
     * @param field
     * @param lineNumber
     * @return
     */
    private static String requiredText(JsonNode node, String field, int lineNumber) {
        // 获取节点值
        JsonNode value = node.get(field);
        // 检查节点值是否为空
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException("第 " + lineNumber + " 行缺少 " + field);
        }
        // 返回节点值
        return value.asText();
    }

    /** 一行知识。meta、embedding 用 JSON 字符串，和表里的 JSON 列对应。 */
    private record ChunkRow(String id, String source, String text, String meta, String embedding) {
    }
}
