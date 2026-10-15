package cn.szu.urbantravelplanningagent.tool;

import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;

/**
 * 获取当前时间工具类
 */
public class GetCurrentDateTimeTool {

    @Tool(description = "需要获取当前时间时，可以调用此工具")
    public String getCurrentDateTime() {
        return LocalDateTime.now().toString();
    }
}
