package kvo.itsnn.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class RagApplication {
    public static void main(String[] args) {
        System.setProperty("java.naming.referral", "ignore"); // AD: не падать на referral-записях
        SpringApplication.run(RagApplication.class, args); }
}