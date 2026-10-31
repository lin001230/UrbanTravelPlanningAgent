package cn.szu.urbantravelplanningagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import cn.szu.urbantravelplanningagent.tool.TerminateTool;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Slf4j
public class ToolCallingAgent extends ReActAgent {

    // 可用的工具
    private ToolCallback[] availableTools;

    // 工具调用的 ChatResponse
//    private ChatResponse toolCallChatResponse;

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
                .multiModel(true)
                // 关闭深度思考
                .enableThinking(false)
                .build();
    }

    /**
     * 推理，并判断是否进行工具调用
     * @return
     */
    @Override
    public boolean think(AgentTurn agentTurn) {
        try {
            // 获取消息列表
            List<Message> promptMessages = new ArrayList<>(agentTurn.getMessageList());
            if (agentTurn.getKnowledgeRetrievalResults() != null) {
                // 加入知识库检索结果，并作为第一个消息
                promptMessages.addFirst(agentTurn.getKnowledgeRetrievalResults());
            }
            // 添加下一阶段提示
            promptMessages.add(new AssistantMessage(this.nextStepPrompt));
            // 构建 Prompt
            Prompt prompt = Prompt.builder().chatOptions(chatOptions).messages(promptMessages).build();
            ChatResponse chatResponse = chatClient
                    .prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(getAvailableTools())
                    .call()
                    .chatResponse();
            // 保存下来推理结果
            agentTurn.setToolCallChatResponse(chatResponse);
            if (chatResponse == null) { // 如果 chatResponse 为null，抛出异常
                log.warn("ChatResponse is null");
                throw new RuntimeException("ChatResponse is null");
            }
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            log.info("Assistant: {}", assistantMessage.getText());
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            String toolListStr = toolCallList.stream().map(toolCall ->
                            StrUtil.join("工具名称", toolCall.name(), "工具参数", toolCall.arguments())).
                    collect(Collectors.joining("\n"));
            log.info("工具调用列表: {}", toolListStr);
            // 将推理结果加入消息列表
            String assistantText = assistantMessage.getText();
            // null/空值 处理
            assistantText = assistantText == null ? "" : assistantText;
            if (!assistantMessage.hasToolCalls()) {
                // 不需要调用工具时，将推理结果加入消息列表
                agentTurn.getMessageList().add(new AssistantMessage(assistantText));
            }
            // 设置推理结果
            agentTurn.setThinkResult(assistantText);
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
    public String act(AgentTurn agentTurn) {
        if (!agentTurn.getToolCallChatResponse().hasToolCalls()) {
            return "不需要进行工具调用";
        }

        try {
            Prompt prompt = Prompt.builder().messages(agentTurn.getMessageList()).chatOptions(chatOptions).build();
            // 执行工具调用
            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, agentTurn.getToolCallChatResponse());
            // 将工具调用结果加入消息列表
            agentTurn.setMessageList(toolExecutionResult.conversationHistory());
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
            String toolResponse = toolResponseMessage.getResponses().stream()
                    // 过滤掉终止工具的调用结果，只保留其他工具的调用结果
                    .filter(response -> !TerminateTool.NAME.equals(response.name()))
                    .map(response -> "工具" + response.name() + "的调用结果：" + response.responseData())
                    .collect(Collectors.joining("\n"));
            log.info("工具调用结果: {}", toolResponse);

            boolean isTerminal = toolResponseMessage.getResponses().stream().anyMatch(response ->
                    TerminateTool.NAME.equals(response.name()));
            if (isTerminal) {
                // 修改模型状态
                agentTurn.setStatus(ModelStatus.FINISHED);
            }
            return toolResponse;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 不再提供工具。只根据已经查到的路线结果写给用户看的方案，并打开思考。
     */
    @Override
    protected String writeFinalAnswer(AgentTurn agentTurn) {
        // 获取消息列表
        List<Message> promptMessages = new ArrayList<>(agentTurn.getMessageList());
        if (agentTurn.getKnowledgeRetrievalResults() != null) {
            // 加入知识库检索结果，并作为第一个消息
            promptMessages.addFirst(agentTurn.getKnowledgeRetrievalResults());
        }
        promptMessages.add(new SystemMessage("工具已经查询完成。请只根据已有结果，用中文写出完整的地铁出行方案。不要调用任何工具。"));
        DashScopeChatOptions answerOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .multiModel(true)
                // 打开深度思考
                .enableThinking(true)
                .build();
        String answer = chatClient.prompt()
                .system(getSystemPrompt())
                .messages(promptMessages)
                .options(answerOptions)
                .call()
                .content();
        // null/空值 处理
        answer = answer == null ? "" : answer.strip();
        // 记录最终方案字数
        log.info("最终方案字数: {}", answer.length());
        // 将最终方案加入消息列表
        List<Message> messages = new ArrayList<>(agentTurn.getMessageList());
        messages.add(new AssistantMessage(answer));
        agentTurn.setMessageList(messages);
        agentTurn.setThinkResult(answer);
        return answer;
    }
}
