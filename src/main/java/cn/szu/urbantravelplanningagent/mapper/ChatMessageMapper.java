package cn.szu.urbantravelplanningagent.mapper;

import cn.szu.urbantravelplanningagent.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    /**
     * 根据 chatId 获取聊天消息列表
     * @param chatId
     * @return
     */
    @Select("select * from chat_message where chat_id = #{chatId} order by id asc")
    List<ChatMessageEntity> getByChatId(String chatId);

    /**
     * 插入聊天消息
     * @param chatMessageEntity
     */
    @Insert("insert into chat_message (chat_id, turn_id, role, content, payload, created_at) values (#{chatId}, #{turnId}, #{role}, #{content}, #{payload}, #{createdAt})")
    void insert(ChatMessageEntity chatMessageEntity);

    /**
     * 根据 chatId 和 turnId 删除聊天消息
     * @param chatId
     * @param turnId
     */
    @Delete("delete from chat_message where chat_id = #{chatId} and turn_id = #{turnId}")
    void deleteByChatIdAndTurnId(@Param("chatId") String chatId, @Param("turnId") String turnId);

    /**
     * 根据 chatId 删除聊天消息
     * @param chatId
     */
    @Delete("delete from chat_message where chat_id = #{chatId}")
    void deleteByChatId(String chatId);
}
