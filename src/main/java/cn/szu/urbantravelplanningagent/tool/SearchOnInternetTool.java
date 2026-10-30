package cn.szu.urbantravelplanningagent.tool;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SearchOnInternetTool {

    /** DeepSeek 网页搜索接口 */
    private static final String SEARCH_API_URL = "https://api.deepseek.com/v1/tools/search";

    /** 搜索请求超时时间（毫秒） */
    private static final int SEARCH_TIMEOUT_MILLIS = 15000;

    /** 最多返回给模型的结果条数 */
    private static final int MAX_RESULTS = 5;

    /** DeepSeek Search 的 query 最长 128 个字符 */
    private static final int MAX_QUERY_LENGTH = 128;

    /**
     * 使用 DeepSeek Search API 进行网络搜索。
     * 失败时返回给模型可读的错误字符串，不向外抛出。
     */
    @Tool(description = "当基于已有知识库无法回答用户问题时，可以调用此工具，进行联网搜索，不过需要提供关键词")
    public String search(@ToolParam(description = "Search query keyword, at most 128 characters") String query) {
        if (query == null || query.isBlank()) {
            return "search error：搜索关键词不能为空";
        }
        String keyword = query.trim();
        if (keyword.length() > MAX_QUERY_LENGTH) {
            return "search error：搜索关键词超过 128 个字符，请缩短后再搜";
        }

        String apiKey = SpringUtil.getProperty("deepseek-search-api.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return "search error：deepseek-search-api.api-key 未配置，请填写 DeepSeek 的 API Key";
        }

        Map<String, Object> body = new HashMap<>();
        body.put("query", keyword);
        body.put("count", MAX_RESULTS);

        try (HttpResponse response = HttpRequest.post(SEARCH_API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(body))
                .timeout(SEARCH_TIMEOUT_MILLIS)
                .execute()) {
            // 获取搜索结果
            String responseBody = response.body();
            // 获取状态码
            int status = response.getStatus();
            if (status != 200) {
                // 获取状态码对应的错误信息以及响应体中的错误信息
                return "search error：" + status + " " + statusHint(status) + "：" + errorMessage(responseBody);
            }
            if (responseBody == null || responseBody.isBlank() || !JSONUtil.isTypeJSONObject(responseBody)) {
                return "search error：搜索响应不是合法 JSON";
            }
            // 解析并返回搜索结果
            return formatResults(keyword, JSONUtil.parseObj(responseBody));
        } catch (Exception e) {
            String message = e.getMessage();
            return "search error：" + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }
    }

    /** 格式化搜索结果。 */
    private String formatResults(String keyword, JSONObject jsonObject) {
        JSONArray results = jsonObject.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return "no search results found for：" + keyword;
        }

        // 构建搜索结果的字符串表示
        StringBuilder formatted = new StringBuilder("search results for：").append(keyword);
        int count = Math.min(results.size(), MAX_RESULTS);
        // 遍历搜索结果并构建字符串
        for (int i = 0; i < count; i++) {
            JSONObject item = results.getJSONObject(i);
            String summary = item.getStr("summary");
            if (summary == null || summary.isBlank()) {
                summary = item.getStr("snippet");
            }
            formatted.append("\n\n")
                    .append(i + 1).append(". ").append(item.getStr("title"))
                    .append("\nlink: ").append(item.getStr("url"))
                    .append("\nsummary: ").append(summary);
        }
        return formatted.toString();
    }

    /** 返回不同状态码对应的提示信息，方便模型决定要不要换关键词或停止重试。 */
    private String statusHint(int status) {
        return switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "API Key 无效";
            case 402 -> "账户余额不足";
            case 429 -> "调用过于频繁";
            case 500, 502, 503, 504 -> "搜索服务暂时不可用";
            default -> "请求失败";
        };
    }

    /** 从搜索响应中提取错误信息。 */
    private String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "empty error response";
        }
        // 尝试从 JSON 响应中提取错误信息
        if (JSONUtil.isTypeJSONObject(body)) {
            String message = JSONUtil.parseObj(body).getByPath("error.message", String.class);
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        // 如果 JSON 解析失败，返回响应体的紧凑表示
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200);
    }
}
