package cn.szu.urbantravelplanningagent.knowledge;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用重写后的用户问题检索知识库。
 * BaseAgent 在查询重写之后调用 {@link #retrieve(String)}，把返回的句子放进推理上下文。
 * 嵌入模型必须是 text-embedding-v4，维度 1024，与入库时一致。
 */
@Component
public class KnowledgeRetriever {

    /** 返回给模型的知识条数。 */
    private static final int TOP_K = 5;

    /** 与 KnowledgeEmbedCommand、knowledge_chunk.embedding 的维度一致。 */
    private static final int EMBEDDING_DIMENSION = 1024;

    /** 嵌入模型： text-embedding-v4 */
    private static final String EMBEDDING_MODEL = DashScopeModel.EmbeddingModel.EMBEDDING_V4.getValue();

    /** 从 knowledge_chunk 读取文本和向量。 */
    private static final String SELECT_SQL = """
            select text, embedding from knowledge_chunk
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 全表读入后复用。表还是空的时候不缓存，导入完成后下一次提问会重新读取。 */
    private volatile List<StoredChunk> cachedChunks;

    /**
     * 检索与重写后的问题最相关的知识句子。
     * 依次完成：问题转向量、读取知识库、计算相似度并取前 {@code TOP_K} 条。
     *
     * @param rewrittenQuery 重写后的用户问题
     * @return 按相关程度从高到低排列的原文，没有命中时返回空列表
     */
    public List<String> retrieve(String rewrittenQuery) {
        // 将问题转成向量
        float[] embedQuery = embed(rewrittenQuery);
        // 读取知识库
        List<StoredChunk> storedChunks = loadChunks();
        // 计算相似度并取前 TOP_K 条
        return rank(embedQuery, storedChunks);
    }

    /**
     * 把 rewrittenQuery 变成 1024 维向量。
     */
    private float[] embed(String rewrittenQuery) {
        /** 嵌入模型，用于将文本转换为向量。 */
        DashScopeEmbeddingModel embeddingModel = new DashScopeEmbeddingModel(
                DashScopeApi.builder().apiKey(System.getenv("DASHSCOPE_API_KEY").trim()).build(),
                MetadataMode.NONE,
                DashScopeEmbeddingOptions.builder()
                        .withModel(EMBEDDING_MODEL)
                        .withDimensions(EMBEDDING_DIMENSION)
                        .build()
        );
        return embeddingModel.embed(rewrittenQuery);
    }

    /**
     * 从 knowledge_chunk 读出文本和向量。
     * 全表大约三千条，读到内存后可以复用，不必每次提问都查库。
     */
    private List<StoredChunk> loadChunks() {
        List<StoredChunk> current = cachedChunks;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedChunks != null) {
                return cachedChunks;
            }
            List<StoredChunk> loaded = readChunks();
            if (!loaded.isEmpty()) {
                cachedChunks = List.copyOf(loaded);
            }
            return loaded;
        }
    }

    /** 连接参数与 KnowledgeChunkImportCommand 相同，账号来自 MYSQL_USERNAME、MYSQL_PASSWORD。 */
    private List<StoredChunk> readChunks() {
        String url = "jdbc:mysql://" + env("MYSQL_HOST", "localhost") + ":" + env("MYSQL_PORT", "3306")
                + "/" + env("MYSQL_DATABASE", "urban_travel_planning_agent")
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        List<StoredChunk> loaded = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("MYSQL_USERNAME"), requiredEnv("MYSQL_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String text = resultSet.getString("text");
                float[] embedding = JSON.readValue(resultSet.getString("embedding"), float[].class);
                if (embedding.length != EMBEDDING_DIMENSION) {
                    throw new IllegalStateException("知识向量维度不是 " + EMBEDDING_DIMENSION + "：" + text);
                }
                loaded.add(new StoredChunk(text, embedding));
            }
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalStateException("读取知识库失败", exception);
        }
        return loaded;
    }

    /**
     * 获取环境变量，如果不存在则返回默认值。
     */
    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 获取环境变量，如果不存在则抛出异常。
     */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    /**
     * 用问题向量和知识向量算余弦相似度，取得分最高的若干条文本。
     */
    private List<String> rank(float[] queryVector, List<StoredChunk> chunks) {
        List<ScoredChunk> scored = new ArrayList<>();
        chunks.forEach(chunk -> {
            double cosine = cosine(queryVector, chunk.embedding);
            scored.add(new ScoredChunk(chunk.text, cosine));
        });
        // 按相似度从高到低排序
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.stream()
                .map(ScoredChunk::text)
                .limit(TOP_K)
                .toList();
    }

    /**
     * 计算两个等长向量的余弦相似度。
     */
    private double cosine(float[] left, float[] right) {
        return SimpleVectorStore.EmbeddingMath.cosineSimilarity(left, right);
    }

    /** 内存中的一条知识：检索时只用原文和向量。 */
    private record StoredChunk(String text, float[] embedding) {
    }

    /** 带分值的文本，用于排序。 */
    private record ScoredChunk(String text, double score){
    }
}
