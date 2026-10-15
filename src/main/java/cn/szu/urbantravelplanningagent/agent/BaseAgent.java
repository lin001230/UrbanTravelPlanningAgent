package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
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

    // 模型名称
    public String name;
    // 模型状态，默认为空闲
    public volatile ModelStatus status = ModelStatus.IDLE;
    // 系统提示词
    public String systemPrompt;
    // 下一步提示词
    public String nextStepPrompt;
    // 聊天客户端
    public ChatClient chatClient;
    // 当前 chatId 对应的聊天消息列表
    public List<Message> messageList;
    // 对于一个问题最大执行步数
    public final int MAX_STEPS = 20;
    // 当前执行步数
    public int currentStep = 1;
    // 模型的响应内容
    public String responseContent;

    /**
     * 当用户输入消息时，模型的响应
     * @param userMessage
     * @return
     */
    /*public String run(String userMessage, String chatId) {
        // 判断模型状态
        if (this.status != ModelStatus.IDLE) {
            return "模型当前正在处理其他请求，请稍等...";
        }
        // 判断用户消息是否为空
        if (userMessage == null || userMessage.isEmpty()) {
            return "用户消息为空，请输入有效消息...";
        }

        // 初始化运行参数
        this.status = ModelStatus.RUNNING;
        this.currentStep = 1;

        // 根据 chatId 获取聊天消息列表
        this.messageList = chatMessagesService.getChatMessages(chatId, this.chatMessages);
        // 将用户输入的问题添加到消息列表中
        messageList.add(new UserMessage(userMessage));

        try {
            for (int i = 0; i < MAX_STEPS && this.status != ModelStatus.FINISHED; i++) {
                // 执行推理
                responseContent = step();
                log.info("Step {}/{} result: {}", this.currentStep, MAX_STEPS, responseContent);
                this.currentStep++;
            }
            if (this.currentStep == MAX_STEPS) {
                log.info("Model reached max steps. Please check the model logic.");
            }
            if (this.status == ModelStatus.FINISHED) {
                log.info("Model finished. Please check the model logic.");
//                messageList.add(new UserMessage("模型已成功生成回复，请查看回复。"));
            }
        } catch (Exception e) {
            log.error("Error occurred: {}", e.getMessage());
            this.responseContent = "模型在处理请求时发生错误，请稍后重试...";
        } finally {
            this.status = ModelStatus.IDLE;
        }

        this.release();
        return this.responseContent;
    }*/

    /**
     * 流式输出
     * 当用户输入消息时，模型的响应
     * @param userMessage
     * @return
     */
    public synchronized SseEmitter runStream(String userMessage, String chatId) {
        // 创建 SseEmitter对象
        SseEmitter emitter = new SseEmitter(300000L);

        try {
            // 判断模型状态
            if (this.status != ModelStatus.IDLE) {
                sendMessageAndComplete(emitter, "模型当前正在处理其他请求，请稍等...");
                return emitter;
            }
            // 判断用户消息是否为空
            if (userMessage == null || userMessage.isEmpty()) {
                sendMessageAndComplete(emitter, "用户消息为空，请输入有效消息...");
                return emitter;
            }
        } catch (Exception e) {
            emitter.completeWithError(new RuntimeException(e.getMessage()));
            return emitter;
        }

        // 初始化运行参数
        this.status = ModelStatus.RUNNING;
        this.currentStep = 1;
        // 生成本轮的 turnId
        String turnId = UUID.randomUUID().toString();
        try {
            // 先把用户消息进行持久化存储
            chatMessagesService.saveUserMessage(chatId, turnId, userMessage);
        } catch (Exception e) {
            log.error("保存用户消息失败", e);
            // 复位模型状态，避免保存失败后模型永久停留在 RUNNING
            this.status = ModelStatus.IDLE;
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
            // 记录本轮是否成功落库
            boolean successSaved = false;
            try {
                // 查询重写, 也会调用模型
                String rewrittenMessage = rewriteUserMessage(userMessage);
                // 根据 chatId 获取聊天消息列表，包含了最新的用户消息
                this.messageList = chatMessagesService.getChatMessages(chatId);
                // 历史消息列表的长度
                int historyMessageLength = this.messageList.size();
                // 将消息列表中最后一个用户消息替换为重写后的用户消息
                this.messageList.set(this.messageList.size() - 1, new UserMessage(rewrittenMessage));

                if (closed.get()) {
                    return;
                }
                for (int i = 0; i < MAX_STEPS && this.status != ModelStatus.FINISHED && !closed.get(); i++) {
                    // 执行推理
                    responseContent = step();
                    if (closed.get()) {
                        return;
                    }
                    // 处理每一轮的推理结果
                    responseContent = responseContent == null ? "" : responseContent;
                    // 将本轮推理结果发送给客户端
                    emitter.send("第" + this.currentStep + "步的结果为: " + responseContent);
                    log.info("Step {}/{} result: {}", this.currentStep, MAX_STEPS, responseContent);
                    this.currentStep++;
                }
                if (this.currentStep == MAX_STEPS) {
                    log.info("Model reached max steps. Please check the model logic.");
                }
                if (this.status == ModelStatus.FINISHED) {
                    log.info("Model finished. Please check the model logic.");
                }
                if (closed.get()) {
                    return;
                }
                // 获取本轮的聊天消息列表
                List<Message> newMessageList = new ArrayList<>(this.messageList.subList(historyMessageLength, this.messageList.size()));
                // 将本轮的聊天消息保存到数据库中
                chatMessagesService.saveChatMessages(chatId, turnId, newMessageList);
                successSaved = true;
                // 最终事件代表本轮已落库，前端此时可以安全刷新历史。
                if (!closed.get()) {
                    emitter.send("模型最终的输出为：" + this.responseContent);
                }
            } catch (Exception e) {
                log.error("流式请求处理失败", e);
                this.responseContent = "模型在处理请求时发生错误，请稍后重试...";
                try {
                    // 将错误信息反馈给客户端
                    if (!closed.get()) {
                        emitter.send(SseEmitter.event().name("error").data(this.responseContent));
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
                    this.status = ModelStatus.IDLE;
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
     * @param userMessage 用户输入的消息
     * @return 重写后的用户消息
     */
    public String rewriteUserMessage(String userMessage) {
        log.info("Rewriting before user input: {}", userMessage);
        // 创建重写查询对象
        RewriteQueryTransformer rewriteQueryTransformer = RewriteQueryTransformer.builder().chatClientBuilder(chatClient.mutate()).build();
        // 创建查询对象
        Query query = new Query(userMessage);
        // 执行查询重写
        Query transformedQuery = rewriteQueryTransformer.transform(query);
        String rewrittenMessage = transformedQuery.text();
        log.info("Rewriting after user input: {}", rewrittenMessage);
        // 返回重写后的消息
        return rewrittenMessage;
    }


    /**
     * 模型每一步推理的逻辑
     * @return
     */
    public abstract String step();

    /**
     * 释放资源
     */
    public void release(){}

}
