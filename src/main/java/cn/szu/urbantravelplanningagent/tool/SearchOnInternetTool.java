package cn.szu.urbantravelplanningagent.tool;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
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

    /** SearchApi.io 搜索接口地址（Google 引擎） */
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    /** 搜索请求超时时间（毫秒） */
    private static final int SEARCH_TIMEOUT_MILLIS = 15000;

    /** 最多返回给模型的结果条数 */
    private static final int MAX_RESULTS = 5;

    @Tool(description = "当基于已有知识库无法回答用户问题时，可以调用此工具，进行联网搜索，不过需要提供关键词")
    public String search(@ToolParam(description = "Search query keyword") String query) {
        String apiKey = SpringUtil.getProperty("search-api.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return "search error：search-api.api-key 未配置，请在 application-local.yml 中填写 SearchApi 的 API Key";
        }

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("engine", "google");
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);

        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap, SEARCH_TIMEOUT_MILLIS);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            if (jsonObject.containsKey("error")) {
                return "search error：" + jsonObject.getStr("error");
            }

            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "no search results found for：" + query;
            }

            StringBuilder results = new StringBuilder("search results for：" + query);
            int count = Math.min(organicResults.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                JSONObject item = organicResults.getJSONObject(i);
                results.append("\n\n")
                        .append(i + 1).append(". ").append(item.getStr("title"))
                        .append("\nlink: ").append(item.getStr("link"))
                        .append("\nsnippet: ").append(item.getStr("snippet"));
            }
            return results.toString();
        } catch (Exception e) {
            return "search error：" + e.getMessage();
        }
    }
}
