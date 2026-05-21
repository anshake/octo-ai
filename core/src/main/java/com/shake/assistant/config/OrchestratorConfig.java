package com.shake.assistant.config;

import com.shake.assistant.orchestrator.ExecutionPlan;
import com.shake.assistant.orchestrator.PlanExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

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
    ToolCallback executePlanTool(PlanExecutor planExecutor)
    {
        String description = """
                Execute a plan of subagent tasks. Independent tasks run in parallel; \
                tasks listing other tasks in their `dependencies` wait for those to \
                finish and receive their outputs as context. Returns the labeled \
                results of the leaf tasks.
                
                Available subagents (use these exact names as `agentName`):
                %s""".formatted(planExecutor.catalog());

        return FunctionToolCallback.builder("executePlan", planExecutor::execute)
                                   .description(description)
                                   .inputType(ExecutionPlan.class)
                                   .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          @Value("classpath:prompts/orchestrator.md") Resource systemPrompt,
                          ChatMemory chatMemory,
                          ToolCallback executePlanTool)
    {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultToolCallbacks(executePlanTool)
                .defaultSystem(systemPrompt)
                .build();
    }

}
