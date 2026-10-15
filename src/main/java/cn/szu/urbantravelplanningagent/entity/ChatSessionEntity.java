package cn.szu.urbantravelplanningagent.entity;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Builder
@Data
public class ChatSessionEntity {

    private String id;
    private String title;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastActiveAt;
}
