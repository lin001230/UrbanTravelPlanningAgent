package cn.szu.urbantravelplanningagent.agent;

import cn.szu.urbantravelplanningagent.constant.ModelStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public abstract class ReActAgent extends BaseAgent {

    public String thinkResult = "";

    @Override
    public String step() {
        try {
            // 模型进行推理
            boolean shouldAct = think();
            if (shouldAct) {
                // 调用工具
                String actResult = act();
                actResult = actResult == null ? "" : actResult;
                return thinkResult + "\n" + actResult;
            } else {
                setStatus(ModelStatus.FINISHED);
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
    public abstract boolean think();

    /**
     * 模型调用工具的逻辑
     * @return
     */
    public abstract String act();
}
