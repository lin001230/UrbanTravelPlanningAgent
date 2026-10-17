package cn.szu.urbantravelplanningagent.controller;

import cn.szu.urbantravelplanningagent.entity.AppUserEntity;
import cn.szu.urbantravelplanningagent.result.Result;
import cn.szu.urbantravelplanningagent.service.JwtService;
import cn.szu.urbantravelplanningagent.service.UserService;
import cn.szu.urbantravelplanningagent.vo.AppUserVO;
import cn.szu.urbantravelplanningagent.vo.LoginResultVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;
    @Resource
    private JwtService jwtService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result register(@RequestBody AppUserEntity user) {
        try {
            userService.register(user);
            return Result.success("注册成功", null);
        } catch (Exception e) {
            return Result.error("注册失败");
        }
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<LoginResultVO> login(@RequestBody AppUserEntity user) {
        try {
            AppUserEntity loggedIn = userService.login(user);
            AppUserVO profile = AppUserVO.fromEntity(loggedIn);
            String token = jwtService.createToken(profile);
            return Result.success("登录成功", new LoginResultVO(token, profile));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

}
