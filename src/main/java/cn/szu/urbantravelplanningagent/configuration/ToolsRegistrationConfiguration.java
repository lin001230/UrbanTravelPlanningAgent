package cn.szu.urbantravelplanningagent.configuration;

import cn.szu.urbantravelplanningagent.tool.SearchOnInternetTool;
import cn.szu.urbantravelplanningagent.tool.TerminateTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册配置类，用于注册自定义工具
 */
@Configuration
public class ToolsRegistrationConfiguration {

    @Bean
    public ToolCallback[] toolCallbacks(ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        // 联网搜索工具
        SearchOnInternetTool searchOnInternetTool = new SearchOnInternetTool();
        // 终止工具
        TerminateTool terminateTool = new TerminateTool();

        List<ToolCallback> toolCallbackList = new ArrayList<>(List.of(ToolCallbacks.from(
                searchOnInternetTool,
                terminateTool
        )));

        // 合并 MCP 客户端提供的工具
        toolCallbackProvider.stream()
                .flatMap(provider -> List.of(provider.getToolCallbacks()).stream())
                .forEach(toolCallbackList::add);

        return toolCallbackList.toArray(new ToolCallback[0]);
    }
}
