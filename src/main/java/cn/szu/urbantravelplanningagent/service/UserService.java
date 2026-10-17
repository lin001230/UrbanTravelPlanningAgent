package cn.szu.urbantravelplanningagent.service;

import cn.szu.urbantravelplanningagent.entity.AppUserEntity;

public interface UserService {

    /**
     * 用户注册
     */
    void register(AppUserEntity user);

    /**
     * 用户登录
     */
    AppUserEntity login(AppUserEntity user);
}
