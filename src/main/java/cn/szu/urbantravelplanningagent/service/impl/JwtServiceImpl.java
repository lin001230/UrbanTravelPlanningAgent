package cn.szu.urbantravelplanningagent.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.szu.urbantravelplanningagent.configuration.JwtProperties;
import cn.szu.urbantravelplanningagent.service.JwtService;
import cn.szu.urbantravelplanningagent.vo.AppUserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtServiceImpl implements JwtService {

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";

    @Resource
    private JwtProperties jwtProperties;

    @Override
    public String createToken(AppUserVO user) {
        Date expiresAt = new Date(System.currentTimeMillis() + jwtProperties.getExpireHours() * 3600_000L);
        return JWT.create()
                .setPayload(CLAIM_USER_ID, user.id())
                .setPayload(CLAIM_USERNAME, user.username())
                .setPayload(CLAIM_NICKNAME, StrUtil.nullToEmpty(user.nickname()))
                .setExpiresAt(expiresAt)
                .setKey(secretBytes())
                .sign();
    }

    @Override
    public AppUserVO parseToken(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        try {
            JWT jwt = JWT.of(token).setKey(secretBytes());
            if (!jwt.verify() || !jwt.validate(0)) {
                return null;
            }
            Long userId = jwt.getPayload(CLAIM_USER_ID) != null
                    ? Long.valueOf(jwt.getPayload(CLAIM_USER_ID).toString()) : null;
            String username = jwt.getPayload(CLAIM_USERNAME) != null
                    ? jwt.getPayload(CLAIM_USERNAME).toString() : null;
            String nickname = jwt.getPayload(CLAIM_NICKNAME) != null
                    ? jwt.getPayload(CLAIM_NICKNAME).toString() : "";
            if (userId == null || StrUtil.isBlank(username)) {
                return null;
            }
            return new AppUserVO(userId, username, nickname);
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] secretBytes() {
        return jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
    }
}
