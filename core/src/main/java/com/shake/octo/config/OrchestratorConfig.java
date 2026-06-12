package com.shake.octo.config;

import com.shake.octo.orchestrator.PlanExecutor;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.anthropic.AnthropicCacheStrategy.SYSTEM_AND_TOOLS;

@Configuration
public class OrchestratorConfig
{

    @Bean
    ChatMemory chatMemory(JdbcTemplate jdbcTemplate)
    {
        var repository = JdbcChatMemoryRepository.builder()
                                                 .jdbcTemplate(jdbcTemplate)
                                                 .build();
        return MessageWindowChatMemory.builder()
                                      .chatMemoryRepository(repository)
                                      .maxMessages(10)
                                      .build();
    }

    @Bean
    ChatClient orchestratorChatClient(ChatClient.Builder builder,
                                      @Value("classpath:prompts/orchestrator.md") Resource systemPrompt,
                                      ChatMemory chatMemory,
                                      PlanExecutor planExecutor)
    {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultSystem(s -> s.text(systemPrompt).param("subagents", planExecutor.listAgents()))
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultOptions(AnthropicChatOptions
                                        .builder()
                                        .cacheOptions(AnthropicCacheOptions
                                                              .builder()
                                                              .strategy(SYSTEM_AND_TOOLS)
                                                              .build())
                )
                .build();
    }

}
