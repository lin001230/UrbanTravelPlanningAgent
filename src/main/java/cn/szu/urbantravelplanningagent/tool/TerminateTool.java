package cn.szu.urbantravelplanningagent.tool;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 工具类：终止程序运行
 */
public class TerminateTool {

    /** 与 @Tool 方法名一致。这个结果只用来结束本轮，不展示给用户。 */
    public static final String NAME = "doTerminate";

    @Tool(description = "当任务已全部完成、无需继续推理或调用其他工具时，调用此工具结束本次推理流程；任务尚未完成时不要调用")
    public String doTerminate() {
        return "推理结束。";
    }
}
