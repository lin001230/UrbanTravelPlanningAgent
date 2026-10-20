-- 路小行知识库建表脚本（MySQL 5.7.8+ / 8.x，InnoDB，utf8mb4）
-- 使用方式：在 SQL 窗口整段执行。只创建 knowledge_chunk，不删除、不修改 app_user、chat_session、chat_message。
-- 表已存在时不会重建，已写入的向量会保留。不插入任何数据。
-- 向量由 KnowledgeEmbedCommand 用 text-embedding-v4 计算，每条 1024 维，之后再导入本表。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS urban_travel_planning_agent
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE urban_travel_planning_agent;

-- 一行是一条可检索的知识。四个来源放在同一张表，用 source 区分。
CREATE TABLE IF NOT EXISTS knowledge_chunk (
  id         VARCHAR(255) NOT NULL COMMENT '知识条目ID，如 station:129872、exit:1019501',
  source     VARCHAR(16)  NOT NULL COMMENT '来源：stations=站点，exits=出入口换乘，edges=相邻站，trains=首末班车',
  text       TEXT         NOT NULL COMMENT '送给嵌入模型的中文句子，检索命中后把这句话交给模型',
  meta       JSON         NULL COMMENT '对照信息，如站名、线路、出入口，不参与相似度计算',
  embedding  JSON         NOT NULL COMMENT 'text-embedding-v4 的 1024 维向量，JSON 数组',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写入时间',
  PRIMARY KEY (id),
  KEY idx_knowledge_source (source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '地铁知识库向量表';
