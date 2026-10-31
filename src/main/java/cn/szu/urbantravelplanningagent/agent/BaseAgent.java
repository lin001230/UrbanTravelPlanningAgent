package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import cn.szu.urbantravelplanningagent.knowledge.KnowledgeRetriever;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import cn.szu.urbantravelplanningagent.service.ConversationSummaryService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@Data
public abstract class BaseAgent {

    @Resource
    private ChatMessagesService chatMessagesService;
    @Resource
    private ConversationSummaryService conversationSummaryService;
    @Resource
    private KnowledgeRetriever knowledgeRetriever;

    // 模型名称
    public String name;
    // 系统提示词
    public String systemPrompt;
    // 下一步提示词
    public String nextStepPrompt;
    // 聊天客户端
    public ChatClient chatClient;
    // 对于一个问题最大执行步数
    public final int MAX_STEPS = 20;

    /**
     * 流式输出
     * 当用户输入消息时，模型的响应
     * @param userMessage
     * @return
     */
    public SseEmitter runStream(String userMessage, String chatId) {
        // 创建 SseEmitter对象
        SseEmitter emitter = new SseEmitter(300000L);

        try {
            // 判断用户消息是否为空
            if (userMessage == null || userMessage.isEmpty()) {
                sendMessageAndComplete(emitter, "用户消息为空，请输入有效消息...");
                return emitter;
            }
        } catch (Exception e) {
            emitter.completeWithError(new RuntimeException(e.getMessage()));
            return emitter;
        }

        // 生成本轮的 turnId
        String turnId = UUID.randomUUID().toString();
        try {
            // 先把用户消息进行持久化存储
            chatMessagesService.saveUserMessage(chatId, turnId, userMessage);
        } catch (Exception e) {
            log.error("保存用户消息失败", e);
            sendMessageAndComplete(emitter, "保存用户消息失败，请稍后重试...");
            return emitter;
        }

        AtomicBoolean closed = new AtomicBoolean();
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时，关闭连接。");
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(error -> closed.set(true));
        emitter.onCompletion(() -> closed.set(true));

        // 异步执行，模型每得到一步结果，都返回给客户端
        Thread.startVirtualThread(() -> {
            AgentTurn agentTurn = new AgentTurn();
            // 初始化运行参数
            agentTurn.setStatus(ModelStatus.RUNNING);
            agentTurn.setCurrentStep(1);

            // 记录本轮是否成功落库
            boolean successSaved = false;
            try {
                // 新的一轮已经入库。压缩滑出窗口的旧轮，并把摘要加最近几轮作为模型上下文。
                agentTurn.setMessageList(conversationSummaryService.compact(chatId));
                // 历史消息列表的长度
                int historyMessageLength = agentTurn.getMessageList().size();
                // 查询重写, 也会调用模型
                // 重写后的用户消息只用于知识库检索
                String rewrittenMessage = rewriteUserMessage(chatId, turnId, userMessage);
                // 获取知识库检索结果
                List<String> knowledgeResults = this.knowledgeRetriever.retrieve(rewrittenMessage);
                if (knowledgeResults != null && !knowledgeResults.isEmpty()) {
                    // 只有检索到知识的时候，才生成知识提示词
                    String knowledgeStr = "以下是地铁知识库检索到的资料，回答时可以引用：";
                    int index = 1;
                    for (String result : knowledgeResults) {
                        knowledgeStr += "\n" + index + ". " + result;
                        index++;
                    }
                    agentTurn.setKnowledgeRetrievalResults(new SystemMessage(knowledgeStr));
                }

                if (closed.get()) {
                    return;
                }
                for (int i = 0; i < MAX_STEPS && agentTurn.getStatus() != ModelStatus.FINISHED && !closed.get(); i++) {
                    // 执行推理
                    agentTurn.setResponseContent(step(agentTurn));
                    if (closed.get()) {
                        return;
                    }
                    // 处理每一轮的推理结果
                    agentTurn.setResponseContent(agentTurn.getResponseContent() == null ? "" : agentTurn.getResponseContent());
                    // 将本轮推理结果发送给客户端
                    emitter.send("第" + agentTurn.getCurrentStep() + "步的结果为: " + agentTurn.getResponseContent());
                    log.info("Step {}/{} result: {}", agentTurn.getCurrentStep(), MAX_STEPS, agentTurn.getResponseContent());
                    agentTurn.setCurrentStep(agentTurn.getCurrentStep() + 1);
                }
                if (agentTurn.getCurrentStep() == MAX_STEPS) {
                    log.info("Model reached max steps. Please check the model logic.");
                }
                if (agentTurn.getStatus() == ModelStatus.FINISHED) {
                    log.info("Model finished. Please check the model logic.");
                }
                if (closed.get()) {
                    return;
                }
                // 获取本轮的聊天消息列表
                List<Message> newMessageList = new ArrayList<>(agentTurn.getMessageList().subList(historyMessageLength, agentTurn.getMessageList().size()));
                // 将本轮的聊天消息保存到数据库中
                chatMessagesService.saveChatMessages(chatId, turnId, newMessageList);
                successSaved = true;
                // 最终事件代表本轮已落库，前端此时可以安全刷新历史。
                if (!closed.get()) {
                    emitter.send("模型最终的输出为：" + agentTurn.getResponseContent());
                }
            } catch (Exception e) {
                log.error("流式请求处理失败", e);
                agentTurn.setResponseContent("模型在处理请求时发生错误，请稍后重试...");
                try {
                    // 将错误信息反馈给客户端
                    if (!closed.get()) {
                        emitter.send(SseEmitter.event().name("error").data(agentTurn.getResponseContent()));
                    }
                } catch (IOException | IllegalStateException ex) {
                    log.warn("向客户端发送响应时发生错误: {}", ex.getMessage());
                }
            } finally {
                closed.set(true);
                if (!successSaved) {
                    // 如果本轮推理过程中发生错误，则根据 chatId和 turnId 清理数据库
                    try {
                        // 执行清理操作
                        chatMessagesService.cleanup(chatId, turnId);
                    } catch (Exception e) {
                        log.error("清理残留聊天消息失败, chatId: {}, turnId: {}", chatId, turnId, e);
                    }
                }
                try {
                    // 等后台调用退出后再释放资源，避免断连回调与模型执行冲突。
                    this.release();
                } finally {
                    agentTurn.setKnowledgeRetrievalResults(null);
                    agentTurn.setStatus(ModelStatus.IDLE);
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    /**
     * 以 SSE 消息形式返回提示信息并正常结束
     * @param emitter
     * @param message
     */
    public void sendMessageAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(message);
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 对用户输入的消息进行重写
     * @param chatId 会话 ID
     * @param turnId 当前轮次，不放进重写历史
     * @param userMessage 用户输入的消息
     * @return 重写后的用户消息
     */
    public String rewriteUserMessage(String chatId, String turnId, String userMessage) {
        log.info("Rewriting before user input: {}", userMessage);
        List<Message> history = conversationSummaryService.recentDialogue(chatId, turnId);
        // 创建查询对象
        Query query = Query.builder().text(userMessage).history(history).build();
        // 创建查询重写器
        QueryTransformer queryTransformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(this.chatClient.mutate())
                .build();
        // 执行查询重写
        Query transformedQuery = queryTransformer.transform(query);
        // 获取重写后的消息
        String rewrittenMessage = transformedQuery.text();
        log.info("Rewriting after user input: {}", rewrittenMessage);
        // 返回重写后的消息
        return rewrittenMessage;
    }


    /**
     * 模型每一步推理的逻辑
     * @return
     */
    public abstract String step(AgentTurn agentTurn);

    /**
     * 释放资源
     */
    public void release(){}

}
