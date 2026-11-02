package cn.szu.urbantravelplanningagent.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.szu.urbantravelplanningagent.constant.SessionStatus;
import cn.szu.urbantravelplanningagent.context.LoginUserContext;
import cn.szu.urbantravelplanningagent.dto.ChatMessageView;
import cn.szu.urbantravelplanningagent.entity.ChatMessageEntity;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.mapper.ChatMessageMapper;
import cn.szu.urbantravelplanningagent.mapper.ChatSessionMapper;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import cn.szu.urbantravelplanningagent.tool.TerminateTool;
import cn.szu.urbantravelplanningagent.tool.TransitRouteTool;
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

    /**
     * 获取聊天历史
     * @param chatId
     * @return
     */
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
        // 遍历回合
        for (var entry : turns.entrySet()) {
            List<ChatMessageView.Step> steps = new ArrayList<>();
            List<TransitRouteTool.RoutePlan> routes = List.of();
            for (ChatMessageEntity row : entry.getValue()) {
                Message message = toMessage(row);
                if (message instanceof UserMessage) {
                    history.add(new ChatMessageView("user-" + row.getId(), entry.getKey(),
                            "user", message.getText(), List.of()));
                } else if (message instanceof AssistantMessage) {
                    steps.add(new ChatMessageView.Step(steps.size() + 1, StrUtil.nullToEmpty(message.getText())));
                    // 读取路线
                    List<TransitRouteTool.RoutePlan> parsed = readRoutes(row.getPayload());
                    if (!parsed.isEmpty()) {
                        routes = parsed;
                    }
                } else if (message instanceof ToolResponseMessage tool) {
                    String toolText = tool.getResponses().stream()
                            .filter(response -> !TerminateTool.NAME.equals(response.name()))
                            .map(response -> "工具" + response.name() + "的调用结果：" + response.responseData())
                            .collect(Collectors.joining("\n"));
                    if (toolText.isEmpty()) {
                        continue;
                    }
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
                        "assistant", steps.getLast().content(), List.copyOf(steps), routes));
            }
        }
        return history;
    }

    /** 最终回复的 payload 才有 routes。工具调用消息里没有这个字段。 */
    private List<TransitRouteTool.RoutePlan> readRoutes(String payload) {
        // 如果 payload 为空或不是 JSON 对象，则返回空列表
        if (StrUtil.isBlank(payload) || !JSONUtil.isTypeJSONObject(payload)) {
            return List.of();
        }
        // 获取 routes 数组
        JSONArray array = JSONUtil.parseObj(payload).getJSONArray("routes");
        // 如果 routes 数组为空，则返回空列表
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        // 创建方案列表
        List<TransitRouteTool.RoutePlan> plans = new ArrayList<>();
        // 遍历 routes 数组
        for (Object item : array) {
            // 如果 item 不是 JSON 对象，则跳过
            if (!(item instanceof JSONObject plan)) {
                continue;
            }
            // 添加方案
            plans.add(new TransitRouteTool.RoutePlan(
                    plan.getInt("durationMinutes", 0),
                    plan.getInt("distanceMeters", 0),
                    plan.getInt("walkingMeters", 0),
                    readLegs(plan.getJSONArray("legs"))));
        }
        // 返回方案列表
        return List.copyOf(plans);
    }

    /**
     * 读取路线
     * @param legs
     * @return
     */
    private List<TransitRouteTool.Leg> readLegs(JSONArray legs) {
        // 如果 legs 为空，则返回空列表
        if (legs == null || legs.isEmpty()) {
            return List.of();
        }
        List<TransitRouteTool.Leg> result = new ArrayList<>();
        // 遍历 legs 数组
        for (Object item : legs) {
            // 如果 item 不是 JSON 对象，则跳过
            if (!(item instanceof JSONObject leg)) {
                continue;
            }
            // 添加路线
            result.add(new TransitRouteTool.Leg(
                    StrUtil.nullToEmpty(leg.getStr("mode")),
                    leg.getInt("distanceMeters", 0),
                    leg.getInt("durationMinutes", 0),
                    StrUtil.nullToEmpty(leg.getStr("line")),
                    StrUtil.nullToEmpty(leg.getStr("direction")),
                    StrUtil.nullToEmpty(leg.getStr("board")),
                    StrUtil.nullToEmpty(leg.getStr("boardExit")),
                    StrUtil.nullToEmpty(leg.getStr("alight")),
                    StrUtil.nullToEmpty(leg.getStr("alightExit")),
                    readStrings(leg.getJSONArray("stops")),
                    StrUtil.nullToEmpty(leg.getStr("price")),
                    StrUtil.nullToEmpty(leg.getStr("notice")),
                    readStrings(leg.getJSONArray("alternatives"))));
        }
        // 返回路线列表
        return List.copyOf(result);
    }

    /**
     * 读取字符串列表
     * @param array
     * @return
     */
    private List<String> readStrings(JSONArray array) {
        // 如果 array 为空，则返回空列表
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        // 遍历 array 数组
        for (Object item : array) {
            // 如果 item 不为空且不是空白字符串，则添加到 values 列表中
            if (item != null && !item.toString().isBlank()) {
                values.add(item.toString());
            }
        }
        return List.copyOf(values);
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
        // 获取最后一条消息
        Message last = messages.isEmpty() ? null : messages.getLast();
        // 获取当前方案
        List<TransitRouteTool.RoutePlan> plans = TransitRouteTool.currentPlans();
        try {
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
                } else if (message == last && message instanceof AssistantMessage assistantMessage
                        && !assistantMessage.hasToolCalls() && !plans.isEmpty()) {
                    // 最终回复没有工具调用。路线只挂在这一条上，供之后打开记录时重画卡片。
                    chatMessageEntity.setPayload(JSONUtil.toJsonStr(Map.of("routes", plans)));
                }
                chatMessageMapper.insert(chatMessageEntity);
            });
        } finally {
            // 清空当前方案
            TransitRouteTool.clearCurrentPlans();
        }
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
        if (chatSessionMapper.updateChatTitleById(chatId, normalizedTitle, LoginUserContext.getUserId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已删除");
        }
    }

}
