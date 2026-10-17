package cn.szu.urbantravelplanningagent.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 把滑出窗口的早期轮次压缩进会话摘要。页面历史仍保留原文。
 */
public interface ConversationSummaryService {

    /** 保留原文送给模型的最近轮数。 */
    int WINDOW_SIZE = 4;

    /** 查询重写时附带的已完成轮数。 */
    int REWRITE_HISTORY_TURNS = 2;

    /**
     * 在本轮提问进入模型之前调用。
     * 轮数不超过窗口时返回全部原文；超出后把滑出的整轮并进摘要，并返回「摘要 + 最近几轮」。
     */
    List<Message> compact(String chatId);

    /**
     * 用模型把已有摘要和刚滑出窗口的对话合并成新摘要。
     *
     * @param existingSummary 会话上已有的摘要，还没有摘要时为空
     * @param slidOutTurns    本次滑出窗口的轮次文本
     * @return 合并后的摘要正文
     */
    String summarize(String existingSummary, String slidOutTurns);

    /**
     * 取当前提问之前最近两轮的用户问题和最终回答。
     * 不包含当前这句，也不包含工具调用和中间推理。
     *
     * @param chatId 会话 ID
     * @param currentTurnId 正在处理的这一轮，从历史中排除
     */
    List<Message> recentDialogue(String chatId, String currentTurnId);
}
