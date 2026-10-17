package cn.szu.urbantravelplanningagent.context;

import cn.szu.urbantravelplanningagent.entity.AppUserEntity;
import cn.szu.urbantravelplanningagent.vo.AppUserVO;

/**
 * 当前请求线程内的登录用户。在请求入口写入，在请求结束 {@link #clear()}，避免线程池复用时泄漏。
 */
public final class LoginUserContext {

    private static final ThreadLocal<AppUserVO> CURRENT = new ThreadLocal<>();

    private LoginUserContext() {
    }

    public static void set(AppUserVO user) {
        if (user == null) {
            clear();
            return;
        }
        CURRENT.set(user);
    }

    public static AppUserVO get() {
        return CURRENT.get();
    }

    public static Long getUserId() {
        AppUserVO user = get();
        return user != null ? user.id() : null;
    }

    public static String getUsername() {
        AppUserVO user = get();
        return user != null ? user.username() : null;
    }

    public static boolean isLoggedIn() {
        return get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
