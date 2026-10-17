package cn.szu.urbantravelplanningagent.entity;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionEntity {

    private String id;
    /** 所属用户 ID */
    private Long userId;
    private String title;
    /** 滑出窗口的早期对话摘要，只用于组装模型上下文。 */
    private String summary;
    /** 摘要已覆盖的最早轮数；之后的轮次仍以原文送给模型。 */
    private Integer summarizedTurnCount;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastActiveAt;
}
