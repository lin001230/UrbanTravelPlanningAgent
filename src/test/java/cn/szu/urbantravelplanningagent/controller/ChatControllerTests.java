package cn.szu.urbantravelplanningagent.controller;

import cn.szu.urbantravelplanningagent.agent.MyAgent;
import cn.szu.urbantravelplanningagent.dto.ChatMessageView;
import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import cn.szu.urbantravelplanningagent.service.ChatMessagesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTests {
    private final ChatMessagesService service = mock(ChatMessagesService.class);
    private final MyAgent agent = mock(MyAgent.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "chatMessagesService", service);
        ReflectionTestUtils.setField(controller, "myAgent", agent);
        // 注册真实 Controller 映射，可发现两个 GET 方法的路由冲突。
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ChatExceptionHandler()).build();
    }

    @Test
    void listAndHistoryHaveDistinctRoutesAndStableResponseShapes() throws Exception {
        when(service.getChatList()).thenReturn(List.of(
                ChatSessionEntity.builder().id("chat-1").title("路线规划").build()));
        when(service.getChatHistory("chat-1")).thenReturn(List.of(
                new ChatMessageView("u1", "t1", "user", "原始提问", List.of())));

        mvc.perform(get("/chat")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("路线规划"));
        mvc.perform(get("/chat/messages").param("chatId", "chat-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("原始提问"));
        mvc.perform(get("/chat/messages")).andExpect(status().isBadRequest());
    }

    @Test
    void streamUsesTheNewChatRouteAndPassesTheConversationId() throws Exception {
        when(agent.runStream(anyString(), anyString())).thenReturn(new SseEmitter());

        mvc.perform(get("/chat/stream").param("message", "去北站").param("chatId", "chat-1"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache"));
        verify(agent).runStream("去北站", "chat-1");
    }

    @Test
    void renameAndDeleteReportFailuresInsteadOfReturningSuccess() throws Exception {
        doThrow(new IllegalArgumentException("标题不能为空"))
                .when(service).updateChatTitle("chat-1", " ");
        mvc.perform(put("/chat").param("chatId", "chat-1").param("title", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));

        doThrow(new DataAccessResourceFailureException("private database detail"))
                .when(service).deleteChat("chat-1");
        mvc.perform(delete("/chat").param("chatId", "chat-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试。"));
    }
}
