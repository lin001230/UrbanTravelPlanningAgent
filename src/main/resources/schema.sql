-- 路小行数据库初始化脚本（MySQL 5.7.8+ / 8.x，InnoDB，utf8mb4）
-- 使用方式：使用具备建库、建表权限的账号，在 SQL 窗口整段执行。
-- 每次执行都会删除已有的 app_user、chat_session、chat_message 并重建为空表，表内数据不保留。
-- 不插入任何初始数据。现有会话代码需接入 user_id 后才能用于新表。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS urban_travel_planning_agent
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE urban_travel_planning_agent;

-- 先按外键依赖的反序删除，再按依赖顺序重建。
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_session;
DROP TABLE IF EXISTS app_user;
SET FOREIGN_KEY_CHECKS = 1;

-- 1. 用户表：先创建，供会话表关联。
CREATE TABLE app_user (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci
                             NOT NULL COMMENT '登录账号：英文字母、数字、下划线，不区分大小写',
  password       VARCHAR(255) NOT NULL COMMENT '登录密码',
  nickname       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '展示昵称，可重复',
  status         TINYINT     NOT NULL DEFAULT 1 COMMENT '账号状态：1=正常，0=禁用',
  last_login_at  DATETIME(3) NULL DEFAULT NULL COMMENT '最近成功登录时间',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '应用用户表';

-- 2. 会话表：一个用户拥有多个会话。
CREATE TABLE chat_session (
  id             VARCHAR(36) NOT NULL COMMENT '会话ID（前端生成的 chatId，UUID）',
  user_id        BIGINT      NOT NULL COMMENT '所属用户ID，由后端从登录身份获取',
  title          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '会话标题（一般取首条用户消息的前若干字符）',
  summary        MEDIUMTEXT  NULL COMMENT '滑出窗口的早期对话摘要，只用于组装模型上下文',
  summarized_turn_count INT  NOT NULL DEFAULT 0 COMMENT '摘要已覆盖的最早轮数；之后的轮次仍以原文送给模型',
  status         TINYINT     NOT NULL DEFAULT 1 COMMENT '会话状态：1=正常，0=停用',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  last_active_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间，每轮对话保存后更新',
  PRIMARY KEY (id),
  KEY idx_last_active_at (last_active_at),
  KEY idx_session_user_status_active (user_id, status, last_active_at, id),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '聊天会话表';

-- 3. 消息表：通过会话关联用户，同一轮问答的多条消息共用 turn_id。
CREATE TABLE chat_message (
  id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键：插入顺序即会话内消息顺序（不要用时间戳排序）',
  chat_id    VARCHAR(36) NOT NULL COMMENT '所属会话ID',
  turn_id    VARCHAR(36) NOT NULL COMMENT '回合标识：一次问答一个 turn；失败回滚与重试清理按它删除',
  role       VARCHAR(16) NOT NULL COMMENT '消息角色：user / assistant / system / tool',
  content    MEDIUMTEXT  NOT NULL COMMENT '消息文本（用户原文或模型输出）',
  payload    JSON        NULL COMMENT '结构化信息：assistant的tool_calls或tool的responses；普通文本消息可为空',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_chat_id_id (chat_id, id),
  KEY idx_chat_turn (chat_id, turn_id),
  CONSTRAINT fk_msg_session FOREIGN KEY (chat_id) REFERENCES chat_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '聊天消息表';
