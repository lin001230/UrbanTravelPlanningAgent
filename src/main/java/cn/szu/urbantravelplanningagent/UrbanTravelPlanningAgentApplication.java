package cn.szu.urbantravelplanningagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

@EnableTransactionManagement
@SpringBootApplication
public class UrbanTravelPlanningAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrbanTravelPlanningAgentApplication.class, args);
    }

}
