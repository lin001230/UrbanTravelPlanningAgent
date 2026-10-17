package cn.szu.urbantravelplanningagent.vo;

import cn.szu.urbantravelplanningagent.entity.AppUserEntity;

/**
 * 返回给前端的用户信息，不包含密码等敏感字段。
 */
public record AppUserVO(Long id, String username, String nickname) {

    public static AppUserVO fromEntity(AppUserEntity entity) {
        if (entity == null) {
            return null;
        }
        return new AppUserVO(entity.getId(), entity.getUsername(), entity.getNickname());
    }
}
