package cn.szu.urbantravelplanningagent.knowledge;

import java.util.Map;

/**
 * 一条待嵌入的知识。text 是实际送进嵌入模型的句子，meta 只是附带信息，方便以后对照原文。
 */
public record KnowledgeText(String id, String source, String text, Map<String, String> meta) {
}
