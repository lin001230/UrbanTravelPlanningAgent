package cn.szu.urbantravelplanningagent.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 云托管存活检查。不访问数据库，也不要求登录。
 * 对外路径是 /api/health（含 context-path）。
 */
@RestController
public class HealthController {

    @GetMapping(value = {"/health", "/healthz"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }
}
