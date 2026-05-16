package com.shake.assistant;

import com.shake.assistant.gateway.telegram.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties(TelegramProperties.class)
public class LocalAssistantApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(LocalAssistantApplication.class, args);
    }

}