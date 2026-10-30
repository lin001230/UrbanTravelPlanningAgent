package cn.szu.urbantravelplanningagent.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.szu.urbantravelplanningagent.context.LoginUserContext;
import cn.szu.urbantravelplanningagent.result.Result;
import cn.szu.urbantravelplanningagent.service.JwtService;
import cn.szu.urbantravelplanningagent.vo.AppUserVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    // 未授权状态码
    private static final int UNAUTHORIZED = 401;

    @Resource
    private JwtService jwtService;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 在处理请求之前执行，用于进行权限验证
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 从请求头中解析出 token
        String token = resolveBearerToken(request);
        // 解析 token
        AppUserVO user = jwtService.parseToken(token);
        if (user == null) {
            // token 解析失败
            writeUnauthorized(response);
            return false;
        }
        // 设置当前用户
        LoginUserContext.set(user);
        return true;
    }

    /**
     * 在请求处理完成后执行，用于清除当前用户信息
     * @param request
     * @param response
     * @param handler
     * @param ex
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LoginUserContext.clear();
    }

    /**
     * 从请求头中解析出 token
     * @param request
     * @return
     */
    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StrUtil.isBlank(header) || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    /**
     * 写入未授权响应
     * @param response
     * @throws Exception
     */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        // 设置状态码
        response.setStatus(UNAUTHORIZED);
        // 设置字符编码和内容类型
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 设置内容类型为 JSON
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 构建响应体
        Result<Void> body = Result.error(UNAUTHORIZED, "未登录或登录已过期");
        // 将响应体写入响应流
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
