package cn.szu.urbantravelplanningagent.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.szu.urbantravelplanningagent.constant.SessionStatus;
import cn.szu.urbantravelplanningagent.context.LoginUserContext;
import cn.szu.urbantravelplanningagent.dto.ChatMessageView;
import cn.szu.urbantravelplanningagent.entity.ChatMessageEntity;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.mapper.ChatMessageMapper;
import cn.szu.urbantravelplanningagent.mapper.ChatSessionMapper;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatMessagesServiceImpl implements ChatMessagesService {

    @Resource
    private ChatMessageMapper chatMessageMapper;
    @Resource
    private ChatSessionMapper chatSessionMapper;


    /**
     * 根据 chatId 获取聊天消息列表
     * @param chatId
     * @return
     */
    @Override
    public List<Message> getChatMessages(String chatId) {
        List<ChatMessageEntity> chatMessageEntities = chatMessageMapper.getByChatId(chatId);
        // Agent 后续会追加消息，因此返回可修改的列表。
        List<Message> messages = new ArrayList<>(chatMessageEntities.size());
        for (ChatMessageEntity entity : chatMessageEntities) {
            messages.add(toMessage(entity));
        }
        return messages;
    }

    @Override
    public List<ChatMessageView> getChatHistory(String chatId) {
        Assert.hasText(chatId, "会话 ID 不能为空");
        ChatSessionEntity session = chatSessionMapper.getById(chatId);
        if (session == null || !Integer.valueOf(SessionStatus.ACTIVE).equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已删除");
        }
        Map<String, List<ChatMessageEntity>> turns = new LinkedHashMap<>();
        for (ChatMessageEntity row : chatMessageMapper.getByChatId(chatId)) {
            turns.computeIfAbsent(row.getTurnId(), ignored -> new ArrayList<>()).add(row);
        }
        List<ChatMessageView> history = new ArrayList<>();
        for (var entry : turns.entrySet()) {
            List<ChatMessageView.Step> steps = new ArrayList<>();
            for (ChatMessageEntity row : entry.getValue()) {
                Message message = toMessage(row);
                if (message instanceof UserMessage) {
                    history.add(new ChatMessageView("user-" + row.getId(), entry.getKey(),
                            "user", message.getText(), List.of()));
                } else if (message instanceof AssistantMessage) {
                    steps.add(new ChatMessageView.Step(steps.size() + 1, StrUtil.nullToEmpty(message.getText())));
                } else if (message instanceof ToolResponseMessage tool) {
                    String toolText = tool.getResponses().stream()
                            .map(response -> "工具" + response.name() + "的调用结果：" + response.responseData())
                            .collect(Collectors.joining("\n"));
                    if (steps.isEmpty()) {
                        steps.add(new ChatMessageView.Step(1, toolText));
                    } else {
                        ChatMessageView.Step step = steps.getLast();
                        steps.set(steps.size() - 1, new ChatMessageView.Step(step.number(),
                                step.content().isEmpty() ? toolText : step.content() + "\n" + toolText));
                    }
                }
            }
            if (!steps.isEmpty()) {
                history.add(new ChatMessageView("assistant-" + entry.getKey(), entry.getKey(),
                        "assistant", steps.getLast().content(), List.copyOf(steps)));
            }
        }
        return history;
    }

    /**
     * 将 ChatMessageEntity 转换为 Message
     * @param entity
     * @return
     */
    Message toMessage(ChatMessageEntity entity) {
        String content = StrUtil.nullToEmpty(entity.getContent());
        return switch (StrUtil.nullToEmpty(entity.getRole())) {
            case "user" -> new UserMessage(content);
            case "system" -> new SystemMessage(content);
            case "assistant" -> AssistantMessage.builder()
                    .content(content)
                    .toolCalls(readPayloadList(entity.getPayload(), "tool_calls", AssistantMessage.ToolCall.class))
                    .build();
            case "tool" -> {
                List<ToolResponseMessage.ToolResponse> responses = readPayloadList(
                        entity.getPayload(), "responses", ToolResponseMessage.ToolResponse.class);
                if (responses.isEmpty()) {
                    throw new IllegalArgumentException("工具消息缺少 responses，消息 ID: " + entity.getId());
                }
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            default -> throw new IllegalArgumentException(
                    "未知消息角色: " + entity.getRole() + "，消息 ID: " + entity.getId());
        };
    }

    /**
     * 解析与保存格式对应的 JSON 数组；普通文本消息可以没有 payload。
     */
    private <T> List<T> readPayloadList(String payload, String key, Class<T> elementType) {
        if (StrUtil.isBlank(payload)) {
            return List.of();
        }
        JSONArray array = JSONUtil.parseObj(payload).getJSONArray(key);
        return array == null ? List.of() : JSONUtil.toList(array, elementType);
    }

    /**
     * 保存本轮聊天的：模型推理的内容、工具调用响应信息
     * @param chatId
     * @param turnId
     * @param messages
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveChatMessages(String chatId, String turnId, List<Message> messages) {
        // 仅更新活跃时间，避免把推理期间用户修改的标题覆盖回旧值。
        chatSessionMapper.touchActivity(chatId, LocalDateTime.now());

        messages.forEach(message -> {
            // 设置共有属性
            ChatMessageEntity chatMessageEntity = ChatMessageEntity.builder()
                    .chatId(chatId)
                    .turnId(turnId)
                    .role(message.getMessageType().getValue())
                    .content(message.getText() == null ? "" : message.getText())
                    .createdAt(LocalDateTime.now())
                    .build();
            // 如果有工具调用，设置 payload 属性值
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                // 模型输出，设置工具调用信息
                chatMessageEntity.setPayload(JSONUtil.toJsonStr(Map.of("tool_calls", assistantMessage.getToolCalls())));
            } else if (message instanceof ToolResponseMessage toolResponseMessage) {
                // 工具调用响应，设置工具调用响应信息
                chatMessageEntity.setPayload(JSONUtil.toJsonStr(Map.of("responses", toolResponseMessage.getResponses())));
            }
            chatMessageMapper.insert(chatMessageEntity);
        });
    }

    /**
     * 用户消息持久化存储
     * @param chatId
     * @param turnId
     * @param userMessage
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveUserMessage(String chatId, String turnId, String userMessage) {
        ChatSessionEntity chatSession = chatSessionMapper.getById(chatId);
        // 判断会话是否存在
        if (chatSession == null) {
            // 新会话，新增会话表记录
            chatSession = ChatSessionEntity.builder()
                    .id(chatId)
                    .userId(LoginUserContext.getUserId())
                    .title(userMessage.substring(0, Math.min(userMessage.length(), 20)))
                    .status(SessionStatus.ACTIVE)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .lastActiveAt(LocalDateTime.now())
                    .build();
            chatSessionMapper.insert(chatSession);
        }
        // 插入用户消息
        chatMessageMapper.insert(ChatMessageEntity.builder()
                        .chatId(chatId)
                        .turnId(turnId)
                        .role("user")
                        .content(userMessage)
                        .createdAt(LocalDateTime.now())
                        .build());
    }

    /**
     * 清理聊天消息
     * @param chatId
     * @param turnId
     */
    @Override
    public void cleanup(String chatId, String turnId) {
        // 删除本轮聊天消息记录
        chatMessageMapper.deleteByChatIdAndTurnId(chatId, turnId);
    }

    /**
     * 删除某一个对话
     * @param chatId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteChat(String chatId) {
        Assert.hasText(chatId, "会话 ID 不能为空");
        // 保留异常传播，让事务回滚，并由 Controller 返回失败状态。
        chatMessageMapper.deleteByChatId(chatId);
        chatSessionMapper.deleteById(chatId);
    }

    /**
     * 获取会话列表
     * @return
     */
    @Override
    public List<ChatSessionEntity> getChatList(Long userId) {
        return chatSessionMapper.list(userId);
    }

    /**
     * 更新会话标题
     * @param chatId
     * @param title
     */
    @Override
    public void updateChatTitle(String chatId, String title) {
        Assert.hasText(chatId, "会话 ID 不能为空");
        Assert.hasText(title, "标题不能为空");
        String normalizedTitle = title.strip();
        Assert.isTrue(normalizedTitle.codePointCount(0, normalizedTitle.length()) <= 64, "标题不能超过 64 个字符");
        if (chatSessionMapper.updateChatTitleById(chatId, normalizedTitle) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已删除");
        }
    }

}
