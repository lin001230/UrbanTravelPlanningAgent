package cn.szu.urbantravelplanningagent.controller;

import cn.szu.urbantravelplanningagent.agent.MyAgent;
import cn.szu.urbantravelplanningagent.context.LoginUserContext;
import cn.szu.urbantravelplanningagent.dto.ChatMessageView;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.result.Result;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatMessagesService chatMessagesService;
    @Resource
    private MyAgent myAgent;

    /**
     * 聊天接口：模型每推理一步，都进行输出
     * @param message
     * @return
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatStream(String message, String chatId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return myAgent.runStream(message, normalizeChatId(chatId));
    }

    /**
     * 前端没有传 chatId 时，为其分配一次性会话，避免所有此类请求共用同一个空值会话
     */
    private String normalizeChatId(String chatId) {
        return chatId == null || chatId.isBlank() ? UUID.randomUUID().toString() : chatId;
    }

    /**
     * 查询会话列表
     */
    @GetMapping
    public Result<List<ChatSessionEntity>> getChatList() {
        return Result.success(chatMessagesService.getChatList(LoginUserContext.getUserId()));
    }

    /**
     * 查询某个会话的消息
     */
    @GetMapping("/messages")
    public Result<List<ChatMessageView>> getChatMessages(@RequestParam String chatId) {
        return Result.success(chatMessagesService.getChatHistory(chatId));
    }

    /**
     * 修改会话标题
     */
    @PutMapping
    public Result<Void> updateChatTitle(@RequestParam String chatId, @RequestParam String title) {
        chatMessagesService.updateChatTitle(chatId, title);
        return Result.success();
    }

    /**
     * 删除某一个会话
     * @param chatId
     * @return
     */
    @DeleteMapping
    public Result<Void> deleteChat(@RequestParam String chatId) {
        chatMessagesService.deleteChat(chatId);
        return Result.success();
    }

}
