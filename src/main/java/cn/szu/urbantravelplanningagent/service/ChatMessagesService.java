package cn.szu.urbantravelplanningagent.service;

import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.dto.ChatMessageView;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface ChatMessagesService {

    /**
     * 根据 chatId 获取聊天消息列表
     */
    List<Message> getChatMessages(String chatId);

    /**
     * 将每轮的对话消息保存进数据库
     */
    void saveChatMessages(String chatId, String turnId, List<Message> messages);

    /**
     * 保存用户消息
     */
    void saveUserMessage(String chatId, String turnId, String userMessage);

    /**
     * 清理残留的聊天消息
     * @param chatId
     * @param turnId
     */
    void cleanup(String chatId, String turnId);

    /**
     * 删除某一个对话
     * @param chatId
     * @return
     */
    void deleteChat(String chatId);

    /** 查询供页面展示的历史消息，按 turnId 汇总执行步骤。 */
    List<ChatMessageView> getChatHistory(String chatId);

    /**
     * 获取会话列表
     * @return
     */
    List<ChatSessionEntity> getChatList();

    /**
     * 更新会话标题
     * @param chatId
     * @param title
     * @return
     */
    void updateChatTitle(String chatId, String title);
}
