package cn.szu.urbantravelplanningagent.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import cn.szu.urbantravelplanningagent.constant.UserStatus;
import cn.szu.urbantravelplanningagent.entity.AppUserEntity;
import cn.szu.urbantravelplanningagent.mapper.UserMapper;
import cn.szu.urbantravelplanningagent.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserMapper userMapper;

    /**
     * 用户注册
     * @param user
     */
    @Override
    public void register(AppUserEntity user) {
        // 合法性验证
        try {
            isValid(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        //判断用户是否存在
        if (userMapper.select(user.getUsername()) != null) {
            throw new RuntimeException("用户名已存在");
        }
        // 对密码进行哈希加密
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        user.setStatus(UserStatus.NORMAL);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 用户登录
     * @param user
     * @return
     */
    @Override
    public AppUserEntity login(AppUserEntity user) {
        // 合法性验证
        try {
            isValid(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 先根据 username 查询用户信息
        AppUserEntity appUser = userMapper.select(user.getUsername());
        // 验证用户名和密码
        if (appUser == null || appUser.getPassword() == null
                || !BCrypt.checkpw(user.getPassword(), appUser.getPassword())) {
            throw new RuntimeException("用户名或密码输入错误");
        }
        // 验证用户状态
        if (appUser.getStatus() == UserStatus.DISABLED) {
            throw new RuntimeException("用户状态异常");
        }
        // 登录成功，更新时间信息
        appUser.setLastLoginAt(LocalDateTime.now());
        appUser.setUpdatedAt(LocalDateTime.now());
        userMapper.update(appUser);

        return appUser;
    }

    /**
     * 合法性验证
     */
    public boolean isValid(AppUserEntity user) {
        // 非空验证
        if (user.getUsername() == null || user.getUsername().isEmpty()) {
            throw new RuntimeException("用户名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new RuntimeException("密码不能为空");
        }
        return true;
    }


}
