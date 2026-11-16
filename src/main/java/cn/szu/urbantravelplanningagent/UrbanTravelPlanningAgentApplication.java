package cn.szu.urbantravelplanningagent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement
@SpringBootApplication(exclude = {
        DashScopeChatAutoConfiguration.class,
        DashScopeAgentAutoConfiguration.class
})
public class UrbanTravelPlanningAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrbanTravelPlanningAgentApplication.class, args);
    }

}
