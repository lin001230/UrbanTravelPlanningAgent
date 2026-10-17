# 登录功能表设计

第一版按“账号 + 密码”注册、登录设计，兼容现有 MySQL 5.7。
完整的新库初始化脚本已整理在 [`src/main/resources/schema.sql`](../src/main/resources/schema.sql)：执行后创建数据库与三张空表。
脚本不会在应用启动时执行，当前登录及会话归属逻辑尚未实现；已有数据库使用下文的迁移步骤，而不是用初始化脚本升级表结构。

## 一、表之间的关系

```text
app_user.id
    └── chat_session.user_id       一个用户拥有多个会话
            └── chat_message.chat_id   一个会话包含多条消息
```

新增用户表，在会话表上保存所有者。消息表保持现有结构，通过所属会话判断访问权限。
本阶段不增加角色、权限、手机验证码、第三方账号绑定等表。

## 二、用户表 app_user

```sql
CREATE TABLE IF NOT EXISTS app_user (
    id             BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID，由后端数据库生成',
    username       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci
                   NOT NULL COMMENT '登录账号，英文字母、数字、下划线，不区分大小写',
    password_hash  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin
                   NOT NULL COMMENT '密码编码结果，包含算法标识、参数和盐等信息',
    nickname       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '展示昵称，可重复',
    status         TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：1=正常，0=禁用',
    last_login_at  DATETIME(3) NULL DEFAULT NULL COMMENT '最近一次成功登录时间',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '应用用户表';
```

| 字段 | 用途与约定 |
| --- | --- |
| `id` | 用户的稳定标识。会话关联此 ID，不关联可变的昵称或账号。Java 使用 `Long`。 |
| `username` | 登录凭据之一。后端限制为 4～32 位英文字母、数字或下划线，统一转为小写保存。唯一索引保证并发注册时也不出现重名。 |
| `password_hash` | 保存 `PasswordEncoder` 的完整输出，例如带 `{bcrypt}` 前缀的编码字符串。禁止保存明文，也不使用 MD5 或普通 SHA-256 直接处理密码。255 字符为更换编码方案预留空间。 |
| `nickname` | 页面显示的名称，可以重复。注册时可默认使用账号名。 |
| `status` | 正常或禁用，不表示是否在线。第一版不提供物理注销用户的功能。 |
| `last_login_at` | 注册后尚未登录时为 NULL；成功登录时更新，不在每个业务请求中更新。 |
| `created_at` / `updated_at` | 保持与现有表相同的毫秒精度时间格式。 |

密码编码器产生的完整字符串已经包含验证所需的盐、参数等信息，本方案不单独增加 `salt` 列。
账号唯一约束独立于 `status`，禁用用户仍占用原账号名。
MySQL 5.7 不依赖 CHECK 约束完成这些校验，账号格式、长度和状态取值由后端校验。

## 三、会话表增加用户归属

新建空库时，目标设计是给 `chat_session` 增加以下内容，其余现有字段保持原样：

```sql
user_id BIGINT NOT NULL COMMENT '所属用户ID',
KEY idx_session_user_status_active (user_id, status, last_active_at, id),
CONSTRAINT fk_session_user FOREIGN KEY (user_id)
    REFERENCES app_user (id)
    ON DELETE RESTRICT
    ON UPDATE RESTRICT
```

索引用于登录后的会话列表查询：

```sql
SELECT id, user_id, title, status, created_at, updated_at, last_active_at
FROM chat_session
WHERE user_id = :currentUserId AND status = 1
ORDER BY last_active_at DESC, id DESC;
```

`:currentUserId` 是说明用的参数；MyBatis 中使用 `#{userId}`。
`currentUserId` 来自后端验证过的登录身份，不能信任前端自行提交的所有者 ID。

这里使用外键限制直接删除仍拥有会话的用户，避免账号删除时意外级联删除全部历史。
现有 `chat_message.chat_id -> chat_session.id` 外键及删除会话时的消息清理逻辑保持原样。

## 四、已有数据如何迁移

现有云端已经保存了匿名会话，不能直接给非空表添加没有有效默认值的 `NOT NULL user_id`。
迁移应配合登录与鉴权代码上线进行，以下步骤不是当前应用可直接执行的完整升级脚本。

1. 创建 `app_user` 表。
2. 同步修改会话实体和 Mapper 的映射、新增及查询逻辑，再添加可空的用户归属字段：

   `ChatSessionEntity` 已增加 `userId` 和无参构造以支持字段映射。以下 SQL 也已整理成可手动执行一次的[旧库升级脚本](../src/main/resources/migrations/001_add_session_user.sql)。登录后的所有者写入、查询和鉴权逻辑仍需后续实现。

   ```sql
   ALTER TABLE chat_session
       ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID，NULL表示迁移前的匿名会话',
       ADD KEY idx_session_user_status_active (user_id, status, last_active_at, id),
       ADD CONSTRAINT fk_session_user FOREIGN KEY (user_id)
           REFERENCES app_user (id)
           ON DELETE RESTRICT ON UPDATE RESTRICT;
   ```

3. 新会话只接受已登录用户，由后端写入其 ID；旧的 `user_id IS NULL` 会话不向普通用户展示。
4. 对于明确属于自己的历史，可在确认归属后按具体会话 ID 回填。归属不明的记录保留为未归属，不直接分配给首个注册用户，也不提供只凭 `chatId` 认领的接口。
5. 如果要求最终所有会话都有用户，先逐一解决旧记录归属。核实没有 NULL 后，再把字段收紧为 NOT NULL：

   ```sql
   SELECT COUNT(*) AS unassigned_sessions
   FROM chat_session
   WHERE user_id IS NULL;

   -- 仅当上面结果为 0 且新代码已确保每次写入 user_id 时执行。
   ALTER TABLE chat_session
       MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '所属用户ID';
   ```

如果旧匿名记录需要继续保留且无法归属，可暂时保持字段可空，所有已登录用户仍只能访问属于自己的记录。
重新执行现有 schema.sql 的 CREATE TABLE IF NOT EXISTS 不会为已存在的表增加此字段，必须执行对应的 ALTER TABLE。

## 五、登录功能实现时的访问边界

新增用户表只提供身份数据，不会自动隔离现有接口。
后续必须同时调整会话列表、历史查询、持续对话、改标题和删除接口：均验证当前用户是会话所有者。
即使其他用户知道 chatId，也不能读取、续写或删除这个会话。
Agent 内部读取历史和异步保存消息时也必须保留本次请求的用户身份，不能依赖跨线程共享的“当前用户”变量。

不在用户表中保存 `is_login` 或单个 `token`。登录状态的保存、过期与退出机制将在选择 Session 或 Token 方案时另行设计；同一账号可以有多个设备的登录状态。

参考：[Spring Security 密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)。
