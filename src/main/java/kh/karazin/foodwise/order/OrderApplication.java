package kh.karazin.foodwise.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"kh.karazin.foodwise.order", "kh.karazin.foodwise.common"})
@EntityScan(basePackages = {"kh.karazin.foodwise.order", "kh.karazin.foodwise.common"})
@EnableJpaRepositories(basePackages = {"kh.karazin.foodwise.order", "kh.karazin.foodwise.common"})
@ConfigurationPropertiesScan("kh.karazin.foodwise.order.config")
@EnableScheduling
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
