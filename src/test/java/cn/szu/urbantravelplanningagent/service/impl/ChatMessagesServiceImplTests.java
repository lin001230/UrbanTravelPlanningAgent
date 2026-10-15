package cn.szu.urbantravelplanningagent.service.impl;

import cn.szu.urbantravelplanningagent.entity.ChatMessageEntity;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.mapper.ChatMessageMapper;
import cn.szu.urbantravelplanningagent.mapper.ChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessagesServiceImplTests {

    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @InjectMocks
    private ChatMessagesServiceImpl service;

    @Test
    void savedMessagesRestoreRolesTextAndToolCallAssociations() {
        var call = new AssistantMessage.ToolCall(
                "call_001", "function", "searchRoute", "{\"destination\":\"深圳北站\"}");
        var response = new ToolResponseMessage.ToolResponse(
                "call_001", "searchRoute", "{\"route\":\"地铁 4 号线\"}");
        List<Message> original = List.of(
                new UserMessage("深圳北站怎么去？"),
                new SystemMessage("请根据工具结果回答。"),
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(List.of(response)).build(),
                new AssistantMessage("建议乘坐地铁。"));

        service.saveChatMessages("chat-1", "turn-1", original);
        var rows = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, times(original.size())).insert(rows.capture());
        when(chatMessageMapper.getByChatId("chat-1")).thenReturn(rows.getAllValues());

        List<Message> restored = service.getChatMessages("chat-1");

        assertEquals(original.stream().map(Message::getMessageType).toList(),
                restored.stream().map(Message::getMessageType).toList());
        assertEquals("深圳北站怎么去？", restored.get(0).getText());
        assertEquals("请根据工具结果回答。", restored.get(1).getText());
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, restored.get(2));
        assertEquals(List.of(call), assistant.getToolCalls());
        ToolResponseMessage tool = assertInstanceOf(ToolResponseMessage.class, restored.get(3));
        assertEquals(List.of(response), tool.getResponses());
        assertEquals("建议乘坐地铁。", restored.get(4).getText());
        assertFalse(((AssistantMessage) restored.get(4)).hasToolCalls());
        assertDoesNotThrow(() -> restored.add(new UserMessage("还有其他路线吗？")));
    }

    @Test
    void newConversationReturnsMutableEmptyHistory() {
        when(chatMessageMapper.getByChatId("new-chat")).thenReturn(List.of());

        List<Message> history = service.getChatMessages("new-chat");

        assertTrue(history.isEmpty());
        assertDoesNotThrow(() -> history.add(new UserMessage("你好")));
    }

    @Test
    void unknownRoleIsRejectedRatherThanTreatedAsTool() {
        when(chatMessageMapper.getByChatId("chat-1")).thenReturn(List.of(
                row("invalid", "{}")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getChatMessages("chat-1"));

        assertTrue(error.getMessage().contains("invalid"));
    }

    @Test
    void toolMessageWithoutResponsesIsRejected() {
        when(chatMessageMapper.getByChatId("chat-1")).thenReturn(List.of(
                row("tool", "{}")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getChatMessages("chat-1"));

        assertTrue(error.getMessage().contains("responses"));
    }

    @Test
    void malformedPayloadIsNotSilentlyDiscarded() {
        when(chatMessageMapper.getByChatId("chat-1")).thenReturn(List.of(
                row("assistant", "not-json")));

        assertThrows(cn.hutool.json.JSONException.class,
                () -> service.getChatMessages("chat-1"));
    }

    @Test
    void historyGroupsStepsByTurnAndKeepsTheFinalAnswerSeparate() {
        when(chatSessionMapper.getById("chat-1")).thenReturn(
                ChatSessionEntity.builder().id("chat-1").status(1).build());
        when(chatMessageMapper.getByChatId("chat-1")).thenReturn(List.of(
                row("user", null, "原始提问", "turn-1"),
                row("system", null, "不展示的提示词", "turn-1"),
                row("assistant", "{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"name\":\"search\",\"arguments\":\"{}\"}]}", "正在查询", "turn-1"),
                row("tool", "{\"responses\":[{\"id\":\"c1\",\"name\":\"search\",\"responseData\":\"路线信息\"}]}", "", "turn-1"),
                row("assistant", null, "最终路线", "turn-1"),
                row("user", null, "继续追问", "turn-2"),
                row("assistant", null, "第二轮回复", "turn-2")));

        var history = service.getChatHistory("chat-1");

        assertEquals(List.of("user", "assistant", "user", "assistant"),
                history.stream().map(cn.szu.urbantravelplanningagent.dto.ChatMessageView::role).toList());
        assertEquals("原始提问", history.getFirst().content());
        assertEquals("最终路线", history.get(1).content());
        assertEquals(2, history.get(1).steps().size());
        assertEquals("正在查询\n工具search的调用结果：路线信息", history.get(1).steps().getFirst().content());
        assertEquals("第二轮回复", history.get(3).content());
    }

    @Test
    void missingConversationReturnsNotFound() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.getChatHistory("missing")).getStatusCode().value());
    }

    @Test
    void renameValidatesAndNormalizesTheTitle() {
        when(chatSessionMapper.updateChatTitleById("chat-1", "新标题")).thenReturn(1);

        service.updateChatTitle("chat-1", "  新标题  ");

        verify(chatSessionMapper).updateChatTitleById("chat-1", "新标题");
        assertThrows(IllegalArgumentException.class, () -> service.updateChatTitle("chat-1", " "));
        assertThrows(IllegalArgumentException.class, () -> service.updateChatTitle("chat-1", "字".repeat(65)));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.updateChatTitle("missing", "新标题")).getStatusCode().value());
    }

    @Test
    void deleteFailurePropagatesSoTheTransactionCanRollBack() {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(chatSessionMapper).deleteById("chat-1");

        assertThrows(DataAccessResourceFailureException.class, () -> service.deleteChat("chat-1"));

        var order = inOrder(chatMessageMapper, chatSessionMapper);
        order.verify(chatMessageMapper).deleteByChatId("chat-1");
        order.verify(chatSessionMapper).deleteById("chat-1");
    }

    private ChatMessageEntity row(String role, String payload, String content, String turnId) {
        return ChatMessageEntity.builder().id(1L).chatId("chat-1").turnId(turnId)
                .role(role).content(content).payload(payload).build();
    }

    private ChatMessageEntity row(String role, String payload) {
        return ChatMessageEntity.builder()
                .id(1L).chatId("chat-1").turnId("turn-1")
                .role(role).content("").payload(payload).build();
    }
}
