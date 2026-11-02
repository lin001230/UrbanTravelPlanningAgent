package cn.szu.urbantravelplanningagent.dto;

import cn.szu.urbantravelplanningagent.tool.TransitRouteTool;

import java.util.List;

/** 页面展示消息；不直接暴露 Spring AI 消息的内部结构。 */
public record ChatMessageView(
        String id,
        String turnId,
        String role,
        String content,
        List<Step> steps,
        List<TransitRouteTool.RoutePlan> routes) {

    /** 没有路线卡片的消息，routes 为空。 */
    public ChatMessageView(String id, String turnId, String role, String content, List<Step> steps) {
        this(id, turnId, role, content, steps, List.of());
    }

    /**
     * 步骤
     * @param number
     * @param content
     */
    public record Step(int number, String content) {}
}
