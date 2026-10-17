package cn.szu.urbantravelplanningagent.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 应用用户，对应 app_user 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUserEntity {

    /** 用户ID，数据库自增。 */
    private Long id;

    /** 登录账号，唯一且不区分大小写。 */
    private String username;

    /** 密码的哈希编码结果，不保存明文 */
    private String password;

    /** 展示昵称，允许重复。 */
    private String nickname;

    /** 账号状态：1=正常，0=禁用。 */
    private Integer status;

    /** 最近成功登录时间，尚未登录时为空。 */
    private LocalDateTime lastLoginAt;

    /** 注册时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
