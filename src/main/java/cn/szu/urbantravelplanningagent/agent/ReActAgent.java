package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public abstract class ReActAgent extends BaseAgent {

//    public String thinkResult = "";

    @Override
    public String step(AgentTurn agentTurn) {
        try {
            // 模型进行推理
            boolean shouldAct = think(agentTurn);
            if (shouldAct) {
                // 调用工具
                String actResult = act(agentTurn);
                actResult = actResult == null ? "" : actResult;
                String thinkResult = agentTurn.getThinkResult() == null ? "" : agentTurn.getThinkResult();
                // 只调用了结束工具时，actResult 为空，避免正文末尾多出一个空行。
                String visible = actResult.isEmpty() ? thinkResult : thinkResult + "\n" + actResult;
                // 结束调用发生之后才知道这是最后一步。正文为空时再单独生成答案。
                if (agentTurn.getStatus() == ModelStatus.FINISHED && visible.isBlank()) {
                    return writeFinalAnswer(agentTurn);
                }
                return visible;
            } else {
                // 没有调用工具时，设置状态为已结束
                agentTurn.setStatus(ModelStatus.FINISHED);
                String thinkResult = agentTurn.getThinkResult() == null ? "" : agentTurn.getThinkResult();
                if (thinkResult.isBlank()) {
                    // 正文为空时再单独生成答案。
                    return writeFinalAnswer(agentTurn);
                }
                return thinkResult;
            }
        } catch (Exception e) {
            log.error("Error occurred: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 模型的思考逻辑
     * @return
     */
    public abstract boolean think(AgentTurn agentTurn);

    /**
     * 模型调用工具的逻辑
     * @return
     */
    public abstract String act(AgentTurn agentTurn);

    /**
     * 工具阶段已经结束，但这次回复没有可展示的正文时，单独再写一次最终方案。
     */
    protected abstract String writeFinalAnswer(AgentTurn agentTurn);
}
