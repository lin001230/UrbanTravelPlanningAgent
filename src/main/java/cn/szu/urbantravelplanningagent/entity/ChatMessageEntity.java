package cn.szu.urbantravelplanningagent.entity;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Builder
@Data
public class ChatMessageEntity {

    private Long id;
    private String chatId;
    private String turnId;
    private String role;
    private String content;

    // 暂时使用 String 承载 JSON，读写时进行序列化和反序列化
    private String payload;

    private LocalDateTime createdAt;
}
