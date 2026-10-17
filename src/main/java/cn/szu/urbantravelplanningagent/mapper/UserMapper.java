package cn.szu.urbantravelplanningagent.mapper;

import cn.szu.urbantravelplanningagent.entity.AppUserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /**
     * 新增用户
     */
    @Insert("INSERT INTO app_user (username, password, nickname, status, created_at, updated_at) VALUES (#{username}, #{password}, #{nickname}, #{status}, #{createdAt}, #{updatedAt})")
    void insert(AppUserEntity user);

    /**
     * 根据用户名查询用户
     * @param username
     * @return
     */
    @Select("SELECT * FROM app_user WHERE username = #{username}")
    AppUserEntity select(String username);

    /**
     * 更新用户信息
     * @param appUser
     */
    void update(AppUserEntity appUser);
}
