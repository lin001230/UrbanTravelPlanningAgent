package cn.szu.urbantravelplanningagent.vo;

/**
 * 登录成功响应：JWT 与前端展示用的用户信息。
 */
public record LoginResultVO(String token, AppUserVO user) {
}
