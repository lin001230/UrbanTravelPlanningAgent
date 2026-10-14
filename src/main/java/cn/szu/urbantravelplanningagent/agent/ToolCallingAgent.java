package cn.szu.urbantravelplanningagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Slf4j
public class ToolCallingAgent extends ReActAgent {

    // 可用的工具
    private ToolCallback[] availableTools;

    // 工具调用的 ChatResponse
    private ChatResponse toolCallChatResponse;

    // 工具调用管理器
    private final ToolCallingManager toolCallingManager;

    // 禁止内置的工具调用机制，自己维护上下文
    private final ChatOptions chatOptions;

    public ToolCallingAgent(ToolCallback[] toolCallbacks) {
        super();
        this.availableTools = toolCallbacks;
        toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .withMultiModel(true)
                .enableThinking(true)
                .build();
    }

    /**
     * 推理，并判断是否进行工具调用
     * @return
     */
    @Override
    public boolean think() {
        if (nextStepPrompt != null && !nextStepPrompt.isEmpty()) {
            getMessageList().add(new AssistantMessage(nextStepPrompt));
        }
        try {
            Prompt prompt = Prompt.builder().chatOptions(chatOptions).messages(getMessageList()).build();
            ChatResponse chatResponse = chatClient
                    .prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(getAvailableTools())
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            log.info("Assistant: {}", assistantMessage.getText());
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String toolListStr = toolCallList.stream().map(toolCall ->
                            StrUtil.join("工具名称", toolCall.name(), "工具参数", toolCall.arguments())).
                    collect(Collectors.joining("\n"));
            log.info("工具调用列表: {}", toolListStr);
            // 将推理结果加入消息列表
            getMessageList().add(new AssistantMessage(assistantMessage.getText()));
            this.setThinkResult(assistantMessage.getText());
            return assistantMessage.hasToolCalls();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行，工具调用
     * @return
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "";
        }

        try {
            Prompt prompt = Prompt.builder().messages(getMessageList()).chatOptions(chatOptions).build();
            // 执行工具调用
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
            // 将工具调用结果加入消息列表
            setMessageList(toolExecutionResult.conversationHistory());
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
            String toolResponse = toolResponseMessage.getResponses().stream().map(response ->
                            "工具" + response.name() + "的调用结果：" + response.responseData())
                    .collect(Collectors.joining("\n"));
            log.info("工具调用结果: {}", toolResponse);

            boolean isTerminal = toolResponseMessage.getResponses().stream().anyMatch(response ->
                    "doTerminate".equals(response.name()));
            if (isTerminal) {
                // 修改模型状态
                setStatus(ModelStatus.FINISHED);
            }
            return toolResponse;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
