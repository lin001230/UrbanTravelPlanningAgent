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
                return actResult.isEmpty() ? thinkResult : thinkResult + "\n" + actResult;
            } else {
                agentTurn.setStatus(ModelStatus.FINISHED);
                return agentTurn.getThinkResult();
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
}
