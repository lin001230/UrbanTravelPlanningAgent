package cn.szu.urbantravelplanningagent.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.szu.urbantravelplanningagent.entity.ChatMessageEntity;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.mapper.ChatMessageMapper;
import cn.szu.urbantravelplanningagent.mapper.ChatSessionMapper;
import cn.szu.urbantravelplanningagent.service.ConversationSummaryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConversationSummaryServiceImpl implements ConversationSummaryService {

    // 摘要的最大长度
    private static final int SUMMARY_MAX_LENGTH = 2000;

    @Resource
    private ChatMessageMapper chatMessageMapper;
    @Resource
    private ChatSessionMapper chatSessionMapper;
    @Resource
    private ChatMessagesServiceImpl chatMessagesService;
    @Resource
    private ChatModel chatModel;

    /**
     * 压缩滑出窗口的旧轮，并把摘要加最近几轮作为模型上下文。
     * @param chatId
     * @return
     */
    @Override
    public List<Message> compact(String chatId) {
        // 将消息列表按 turnId 分组
        List<List<ChatMessageEntity>> turns = groupTurns(chatMessageMapper.getByChatId(chatId));
        if (turns.size() <= WINDOW_SIZE) {  // 如果消息列表长度小于等于窗口大小，则直接返回消息列表
            return toMessages(turns);
        }
        // 获取会话信息
        ChatSessionEntity session = chatSessionMapper.getById(chatId);
        // 获取会话摘要的已覆盖轮次，如果会话信息为空或者会话摘要的已覆盖轮次为空，则返回0
        int covered = session == null || session.getSummarizedTurnCount() == null ? 0 : session.getSummarizedTurnCount();
        // 获取会话摘要的未覆盖轮次
        int target = turns.size() - WINDOW_SIZE;
        if (session != null && covered < target) {
            // 将未覆盖的轮次合并到摘要中
            covered = foldIntoSummary(session, turns.subList(covered, target), covered, target);
        }
        // 组装上下文
        return assemble(session, turns, covered);
    }

    /**
     * 取当前提问之前最近两轮的用户问题和最终回答。
     */
    @Override
    public List<Message> recentDialogue(String chatId, String currentTurnId) {
        List<List<ChatMessageEntity>> completed = new ArrayList<>();
        for (List<ChatMessageEntity> turn : groupTurns(chatMessageMapper.getByChatId(chatId))) {
            if (turn.isEmpty() || turn.getFirst().getTurnId().equals(currentTurnId)) {
                continue;
            }
            if (hasFinalAnswer(turn)) {
                completed.add(turn);
            }
        }
        int from = Math.max(0, completed.size() - REWRITE_HISTORY_TURNS);
        List<Message> history = new ArrayList<>();
        for (int i = from; i < completed.size(); i++) {
            history.addAll(userQuestionAndFinalAnswer(completed.get(i)));
        }
        return history;
    }

    /**
     * 这一轮是否已经有不带工具调用的最终回答。
     */
    private boolean hasFinalAnswer(List<ChatMessageEntity> turn) {
        for (ChatMessageEntity row : turn) {
            Message message = chatMessagesService.toMessage(row);
            if (message instanceof AssistantMessage assistant && !assistant.hasToolCalls()
                    && StrUtil.isNotBlank(assistant.getText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用模型把已有摘要和刚滑出窗口的对话合并成新摘要。
     * @param existingSummary 会话上已有的摘要，还没有摘要时为空
     * @param slidOutTurns 本次滑出窗口的轮次文本
     * @return 合并后的摘要正文
     */
    @Override
    public String summarize(String existingSummary, String slidOutTurns) {
        String prompt = """
                你在维护地铁出行助手的会话摘要。只保留后续规划还会用到的事实：起点、终点、出行偏好、已否定的方案、时间限制。
                不要逐句复述，不要写工具返回的原始数据。控制在 400 字以内。
                已有摘要：
                %s
                新滑出窗口的对话：
                %s
                只输出合并后的摘要正文。
                """.formatted(StrUtil.blankToDefault(existingSummary, "（无）"), slidOutTurns);
        // 调用模型合并摘要
        var response = chatModel.call(new Prompt(new UserMessage(prompt)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            // 如果模型返回结果为空，则返回空字符串
            return "";
        }
        // 返回合并后的摘要正文
        return StrUtil.nullToEmpty(response.getResult().getOutput().getText()).strip();
    }

    /**
     * 将未覆盖的轮次合并到摘要中
     * @param session 会话信息
     * @param slidOutTurns 未覆盖的轮次
     * @param covered 已覆盖的轮次
     * @param target 未覆盖的轮次
     * @return 合并后的轮次
     */
    private int foldIntoSummary(ChatSessionEntity session, List<List<ChatMessageEntity>> slidOutTurns,
                                int covered, int target) {
        try {
            // 合并摘要和未覆盖的轮次
            String merged = summarize(session.getSummary(), renderTurns(slidOutTurns));
            // 如果合并后的摘要为空，则返回已覆盖的轮次
            if (StrUtil.isBlank(merged)) {
                return covered;
            }
            // 如果合并后的摘要长度大于最大长度，则截取最大长度
            if (merged.length() > SUMMARY_MAX_LENGTH) {
                merged = merged.substring(0, SUMMARY_MAX_LENGTH);
            }
            session.setSummary(merged);
            session.setSummarizedTurnCount(target);
            session.setUpdatedAt(LocalDateTime.now());
            chatSessionMapper.updateSummary(session);
            return target;
        } catch (RuntimeException e) {
            log.warn("更新会话摘要失败, chatId: {}", session.getId(), e);
            return covered;
        }
    }

    /**
     * 组装上下文
     * @param session 会话信息
     * @param turns 轮次
     * @param covered 已覆盖的轮次
     * @return 上下文
     */
    private List<Message> assemble(ChatSessionEntity session, List<List<ChatMessageEntity>> turns, int covered) {
        // 获取窗口开始位置
        int windowStart = turns.size() - WINDOW_SIZE;
        // 获取从哪个轮次开始
        int from = Math.min(Math.max(covered, 0), windowStart);
        List<Message> context = new ArrayList<>();
        if (session != null && StrUtil.isNotBlank(session.getSummary()) && from > 0) {
            // 如果会话信息不为空且摘要不为空且从哪个轮次开始大于0，则添加摘要
            context.add(new SystemMessage("更早对话的摘要：\n" + session.getSummary().trim()));
        }
        // 判断是否需要截断工具调用
        boolean trimTools = from == windowStart;
        // 遍历每一轮次
        for (int i = from; i < turns.size(); i++) {
            // 如果需要截断工具调用，则添加用户问题和最终答案，否则添加整个轮次
            context.addAll(trimTools ? userQuestionAndFinalAnswer(turns.get(i)) : toMessages(List.of(turns.get(i))));
        }
        return context;
    }

    /**
     * 添加用户问题和最终答案
     * @param turn 轮次
     * @return 用户问题和最终答案
     */
    private List<Message> userQuestionAndFinalAnswer(List<ChatMessageEntity> turn) {
        // 可见的消息列表
        List<Message> visible = new ArrayList<>();
        // 最终答案
        AssistantMessage finalAnswer = null;
        // 遍历每一轮次
        for (ChatMessageEntity row : turn) {
            Message message = chatMessagesService.toMessage(row);
            if (message instanceof UserMessage) {
                // 如果为用户消息，则添加到可见的消息列表
                visible.add(message);
            } else if (message instanceof AssistantMessage assistant && !assistant.hasToolCalls()) {
                // 如果为助手消息且没有工具调用，则设置最终答案
                finalAnswer = assistant;
            }
        }
        // 如果最终答案不为空，则添加到可见的消息列表
        if (finalAnswer != null) {
            visible.add(finalAnswer);
        }
        return visible;
    }

    /**
     * 将消息列表转换为 Message 列表
     * @param turns
     * @return
     */
    private List<Message> toMessages(List<List<ChatMessageEntity>> turns) {
        List<Message> messages = new ArrayList<>();
        for (List<ChatMessageEntity> turn : turns) {
            for (ChatMessageEntity row : turn) {
                messages.add(chatMessagesService.toMessage(row));
            }
        }
        return messages;
    }

    /**
     * 将消息列表按 turnId 分组
     * @param rows
     * @return
     */
    private List<List<ChatMessageEntity>> groupTurns(List<ChatMessageEntity> rows) {
        Map<String, List<ChatMessageEntity>> turns = new LinkedHashMap<>();
        for (ChatMessageEntity row : rows) {
            turns.computeIfAbsent(row.getTurnId(), ignored -> new ArrayList<>()).add(row);
        }
        return new ArrayList<>(turns.values());
    }

    /**
     * 将轮次转换为文本
     * @param turns
     * @return
     */
    private String renderTurns(List<List<ChatMessageEntity>> turns) {
        // 创建一个 StringBuilder 用于构建文本
        StringBuilder text = new StringBuilder();
        // 遍历每一轮次
        for (List<ChatMessageEntity> turn : turns) {
            // 获取用户消息
            String user = turn.stream().filter(row -> "user".equals(row.getRole()))
                    .map(row -> StrUtil.nullToEmpty(row.getContent()))
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
            String answer = "";
            // 从最后一轮开始遍历，找到最后一轮的答案
            for (int i = turn.size() - 1; i >= 0; i--) {
                // 获取当前轮次的消息
                ChatMessageEntity row = turn.get(i);
                // 判断是否为工具调用
                boolean toolCall = row.getPayload() != null && row.getPayload().contains("tool_calls");
                // 如果为助手消息且内容不为空且不为工具调用，则设置答案
                if ("assistant".equals(row.getRole()) && StrUtil.isNotBlank(row.getContent()) && !toolCall) {
                    answer = row.getContent();
                    break;
                }
            }
            // 将用户消息和答案添加到文本中
            text.append("用户：").append(user).append('\n').append("助手：").append(answer).append('\n');
        }
        return text.toString();
    }
}
