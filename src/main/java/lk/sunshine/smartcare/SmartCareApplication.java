package lk.sunshine.smartcare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartCareApplication {
    public static void main(String[] args) { SpringApplication.run(SmartCareApplication.class, args); }
}
