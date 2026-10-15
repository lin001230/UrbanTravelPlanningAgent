package cn.szu.urbantravelplanningagent.dto;

import java.util.List;

/** 页面展示消息；不直接暴露 Spring AI 消息的内部结构。 */
public record ChatMessageView(String id, String turnId, String role, String content, List<Step> steps) {

    public record Step(int number, String content) {}
}
