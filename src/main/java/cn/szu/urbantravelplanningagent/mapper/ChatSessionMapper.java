package cn.szu.urbantravelplanningagent.mapper;

import cn.szu.urbantravelplanningagent.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ChatSessionMapper {

    /**
     * 判断会话是否存在
     * @param chatId
     * @return
     */
    @Select("SELECT COUNT(*) FROM chat_session WHERE id = #{chatId}")
    boolean isExist(String chatId);

    /**
     * 新增会话信息到数据库
     * @param build
     */
    @Insert("INSERT INTO chat_session (id, user_id, title, status, created_at, updated_at, last_active_at) VALUES (#{id}, #{userId}, #{title}, #{status}, #{createdAt}, #{updatedAt}, #{lastActiveAt})")
    void insert(ChatSessionEntity build);

    /**
     * 根据 id 查询会话信息
     * @param chatId
     * @return
     */
    @Select("SELECT * FROM chat_session WHERE id = #{chatId}")
    ChatSessionEntity getById(String chatId);

    /**
     * 根据 id 更新会话信息
     * @param chatSession
     */
    @Update("update chat_session set title = #{title}, status = #{status}, updated_at = #{updatedAt}, last_active_at = #{lastActiveAt} where id = #{id}")
    void updateById(ChatSessionEntity chatSession);

    /**
     * 根据 id 删除会话信息
     * @param chatId
     */
    @Delete("delete from chat_session where id = #{chatId}")
    void deleteById(String chatId);

    /**
     * 查询会话列表
     * @return
     */
    @Select("select * from chat_session where status = 1 and user_id = #{userId} order by last_active_at desc, id desc")
    List<ChatSessionEntity> list(@Param("userId") Long userId);

    /**
     * 更新会话标题
     * @param chatId
     * @param title
     */
    @Update("update chat_session set title = #{title} where id = #{chatId} and status = 1")
    int updateChatTitleById(@Param("chatId") String chatId, @Param("title") String title);

    @Update("update chat_session set updated_at = #{now}, last_active_at = #{now} where id = #{chatId}")
    void touchActivity(@Param("chatId") String chatId, @Param("now") LocalDateTime now);
}
