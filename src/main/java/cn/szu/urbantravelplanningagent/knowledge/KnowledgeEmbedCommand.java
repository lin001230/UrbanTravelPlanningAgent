package cn.szu.urbantravelplanningagent.knowledge;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线把去重后的地铁知识嵌入成向量，写入 knowledge/output/embeddings.jsonl。
 * <p>
 * 使用通义 text-embedding-v4、1024 维。这和聊天模型 qwen3.8-flash 不是同一个模型。
 * 启动本类不会启动 Web 服务。在 IDEA 里直接运行 main，工作目录选项目根目录，
 * 并设置环境变量 DASHSCOPE_API_KEY。可选参数：--dry-run、--limit 5。
 */
public class KnowledgeEmbedCommand {

    /** text-embedding-v4 一次最多接收 10 条文本。 */
    private static final int BATCH_SIZE = 10;

    private static final int EMBEDDING_DIMENSION = 1024;

    private static final String EMBEDDING_MODEL = DashScopeModel.EmbeddingModel.EMBEDDING_V4.getValue();

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int limit = argumentInt(args, "--limit", 0);
        boolean dryRun = hasArgument(args, "--dry-run");
        // 从 knowledge/output 目录下读取文件
        Path outputDir = findOutputDir();
        // 构建知识文本对象
        List<KnowledgeText> documents = new KnowledgeSentenceBuilder(outputDir).build();

        if (dryRun) {   // 打印样本数据
            printSamples(documents);
            return;
        }

        // 获取通义千问 API 密钥
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {  // 检查环境变量
            throw new IllegalStateException("缺少环境变量 DASHSCOPE_API_KEY");
        }

        // 构建 embeddings.jsonl 文件路径
        Path embeddingsFile = outputDir.resolve("embeddings.jsonl");
        // 加载已经嵌入的 ID
        Set<String> doneIds = loadDoneIds(embeddingsFile);
        List<KnowledgeText> pending = new ArrayList<>();
        for (KnowledgeText document : documents) {
            if (!doneIds.contains(document.id())) { // 如果 ID 不在 doneIds 中，则添加到 pending 列表
                pending.add(document);
            }
        }
        if (limit > 0 && pending.size() > limit) { // 如果 limit 大于 0 且 pending 列表大于 limit，则截取前 limit 条
            pending = pending.subList(0, limit);
        }
        System.out.println("documents " + documents.size() + ", already done " + doneIds.size() + ", pending " + pending.size());

        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel(
                DashScopeApi.builder().apiKey(apiKey.trim()).build(),
                MetadataMode.NONE,
                embeddingOptions()
        );
        // 追加写入。中断后再次运行会跳过已经写入的 id。
        try (BufferedWriter writer = Files.newBufferedWriter(embeddingsFile, StandardCharsets.UTF_8,
                Files.exists(embeddingsFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
            for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
                List<KnowledgeText> batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
                List<String> texts = batch.stream().map(KnowledgeText::text).toList();
                List<float[]> vectors = embedBatchWithRetry(model, texts);
                for (int i = 0; i < batch.size(); i++) {
                    writer.write(JSON.writeValueAsString(toRecord(batch.get(i), vectors.get(i))));
                    writer.newLine();
                }
                writer.flush();
                System.out.println("embedded " + Math.min(start + BATCH_SIZE, pending.size()) + "/" + pending.size());
            }
        }
    }

    private static DashScopeEmbeddingOptions embeddingOptions() {
        // 依赖里的默认嵌入模型是 text-embedding-v3，这里显式指定 v4 和 1024 维。
        return DashScopeEmbeddingOptions.builder()
                .withModel(EMBEDDING_MODEL)
                .withDimensions(EMBEDDING_DIMENSION)
                .build();
    }

    /** 调用一次嵌入接口。返回的向量顺序与 texts 一致。 */
    private static List<float[]> embedBatch(DashScopeEmbeddingModel model, List<String> texts) {
        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, embeddingOptions()));
        List<Embedding> results = new ArrayList<>(response.getResults());
        results.sort(Comparator.comparingInt(Embedding::getIndex));
        if (results.size() != texts.size()) {
            throw new IllegalStateException("嵌入返回条数是 " + results.size() + "，请求条数是 " + texts.size());
        }
        List<float[]> vectors = new ArrayList<>();
        for (Embedding result : results) {
            float[] vector = result.getOutput();
            if (vector == null || vector.length != EMBEDDING_DIMENSION) {
                int actual = vector == null ? 0 : vector.length;
                throw new IllegalStateException("嵌入结果维度不是 " + EMBEDDING_DIMENSION + "，实际是 " + actual);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static List<float[]> embedBatchWithRetry(DashScopeEmbeddingModel model, List<String> texts) throws InterruptedException {
        long delayMillis = 1000;
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return embedBatch(model, texts);
            } catch (RestClientResponseException error) {
                int status = error.getStatusCode().value();
                lastError = error;
                // 限流和临时故障可以等一会再试；密钥或参数错误重试没有意义。
                if (status != 429 && status != 500 && status != 502 && status != 503) {
                    throw error;
                }
            } catch (ResourceAccessException error) {
                lastError = error;
            }
            Thread.sleep(delayMillis);
            delayMillis *= 2;
        }
        throw lastError;
    }

    private static Map<String, Object> toRecord(KnowledgeText document, float[] vector) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", document.id());
        record.put("source", document.source());
        record.put("text", document.text());
        record.put("meta", document.meta());
        record.put("model", EMBEDDING_MODEL);
        record.put("dimension", EMBEDDING_DIMENSION);
        record.put("embedding", vector);
        return record;
    }

    private static Set<String> loadDoneIds(Path file) throws IOException {
        Set<String> done = new LinkedHashSet<>();
        if (!Files.exists(file)) {
            return done;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                done.add(JSON.readTree(line).get("id").asText());
            }
        }
        return done;
    }

    private static void printSamples(List<KnowledgeText> documents) {
        System.out.println("documents " + documents.size());
        Set<String> ids = new LinkedHashSet<>();
        documents.forEach(document -> ids.add(document.id()));
        System.out.println("unique " + ids.size());
        for (String source : List.of("stations", "exits", "edges", "trains")) {
            for (KnowledgeText document : documents) {
                if (source.equals(document.source())) {
                    System.out.println("--- " + source);
                    System.out.println(document.id());
                    System.out.println(document.text());
                    break;
                }
            }
        }
    }

    /** 从当前目录向上找 knowledge/output，这样在 IDEA 里不一定非要手动改工作目录。 */
    private static Path findOutputDir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cursor != null; i++) {
            Path candidate = cursor.resolve("knowledge").resolve("output").resolve("stations.csv");
            if (Files.exists(candidate)) {
                return candidate.getParent();
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到 knowledge/output/stations.csv，请先运行去重脚本");
    }

    private static boolean hasArgument(String[] args, String name) {
        for (String arg : args) {
            if (name.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int argumentInt(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length; i++) {
            if (name.equals(args[i]) && i + 1 < args.length) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }
}
