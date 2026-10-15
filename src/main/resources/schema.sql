-- 对话记忆存储：会话表 + 消息表（MySQL 8.0+，utf8mb4）
-- 使用方式：在腾讯云 MySQL 控制台 SQL 窗口整段执行；脚本可重复执行（IF NOT EXISTS）
-- 注意执行顺序：先 chat_session，再 chat_message（被外键引用）

CREATE TABLE IF NOT EXISTS chat_session (
  id             VARCHAR(36) NOT NULL COMMENT '会话ID（前端生成的 chatId，UUID）',
  title          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '会话标题（一般取首条用户消息的前若干字符）',
  status         TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1=正常，0=已删除（软删）',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  last_active_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间（每轮结束显式更新，供清理任务使用）',
  PRIMARY KEY (id),
  KEY idx_last_active_at (last_active_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天会话表';

CREATE TABLE IF NOT EXISTS chat_message (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键：插入顺序即会话内消息顺序（不要用时间戳排序）',
  chat_id    VARCHAR(36) NOT NULL COMMENT '所属会话ID',
  turn_id    VARCHAR(36) NOT NULL COMMENT '回合标识：一次问答一个 turn；失败回滚与重试清理按它删除',
  role       VARCHAR(16) NOT NULL COMMENT '消息角色：user / assistant / system / tool',
  content    MEDIUMTEXT  NOT NULL COMMENT '消息文本（用户原文或模型输出）',
  payload    JSON        NULL COMMENT '结构化附加信息：tool_calls、工具响应、reasoning_content、重写后的消息等',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_chat_id_id (chat_id, id),
  KEY idx_chat_turn (chat_id, turn_id),
  CONSTRAINT fk_msg_session FOREIGN KEY (chat_id) REFERENCES chat_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '聊天消息表';
