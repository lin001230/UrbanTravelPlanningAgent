package cn.szu.urbantravelplanningagent.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HMAC 密钥，生产环境请通过环境变量覆盖。 */
    private String secret = "urban-travel-planning-agent-secret";

    /** 令牌有效期（小时）。 */
    private int expireHours = 2;
}
