package cn.szu.urbantravelplanningagent.service;

import cn.szu.urbantravelplanningagent.vo.AppUserVO;

public interface JwtService {

    /**
     * 为登录用户签发 JWT。
     */
    String createToken(AppUserVO user);

    /**
     * 校验并解析 JWT。令牌无效或过期时返回 null。
     */
    AppUserVO parseToken(String token);
}
