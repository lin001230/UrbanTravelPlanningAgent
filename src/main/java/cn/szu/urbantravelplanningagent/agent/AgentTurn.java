package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

@Data
public class AgentTurn {
    private List<Message> messageList;
    private int currentStep = 1;
    private ModelStatus status = ModelStatus.IDLE;
    private String responseContent;
    private SystemMessage knowledgeRetrievalResults;
    private String thinkResult;
    private ChatResponse toolCallChatResponse;
}
