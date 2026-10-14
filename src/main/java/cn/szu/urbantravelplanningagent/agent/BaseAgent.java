package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@Data
public abstract class BaseAgent {

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
    // 聊天消息列表
    public List<Message> messageList = new ArrayList<>();
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
    public String run(String userMessage) {
        // 判断模型状态
        if (this.status != ModelStatus.IDLE) {
            return "模型当前正在处理其他请求，请稍等...";
        }
        // 判断用户消息是否为空
        if (userMessage == null || userMessage.isEmpty()) {
            return "用户消息为空，请输入有效消息...";
        }
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
    }

    /**
     * 流式输出
     * 当用户输入消息时，模型的响应
     * @param userMessage
     * @return
     */
    public synchronized SseEmitter runStream(String userMessage) {
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

        AtomicBoolean closed = new AtomicBoolean();
        var heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("agent-sse-heartbeat").factory());
        Runnable closeConnection = () -> {
            closed.set(true);
            heartbeat.shutdownNow();
        };
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时，关闭连接。");
            closeConnection.run();
            emitter.complete();
        });
        emitter.onError(error -> closeConnection.run());
        emitter.onCompletion(closeConnection);

        try {
            // 注释帧会立即刷新响应，但不会作为聊天内容显示在前端。
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            closeConnection.run();
            this.status = ModelStatus.IDLE;
            emitter.completeWithError(e);
            return emitter;
        }
        heartbeat.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                closeConnection.run();
                emitter.completeWithError(e);
            }
        }, 10, 10, TimeUnit.SECONDS);

        // 异步执行，模型每得到一步结果，都返回给客户端
        Thread.startVirtualThread(() -> {
            try {
                // 查询重写也会调用模型，必须放到后台，避免阻塞 SSE 建连。
                String rewrittenMessage = rewriteUserMessage(userMessage);
                if (closed.get()) {
                    return;
                }
                messageList.add(new UserMessage(rewrittenMessage));
                for (int i = 0; i < MAX_STEPS && this.status != ModelStatus.FINISHED && !closed.get(); i++) {
                    // 执行推理
                    responseContent = step();
                    if (closed.get()) {
                        return;
                    }
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
                // 关闭心跳线程池
                closeConnection.run();
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
