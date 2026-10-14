package cn.szu.urbantravelplanningagent.controller;

import cn.szu.urbantravelplanningagent.agent.MyAgent;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/agent")
@Slf4j
public class AgentController {

    @Resource
    private MyAgent myAgent;

    /**
     * 普通聊天接口：模型推理结束才输出最终结果
     * @param message
     * @return
     */
    @GetMapping("/chat")
    public String doChat(String message) {
        return myAgent.run(message);
    }

    /**
     * 聊天接口：模型每推理一步，都进行输出
     * @param message
     * @return
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter doChatStream(String message, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return myAgent.runStream(message);
    }

}
