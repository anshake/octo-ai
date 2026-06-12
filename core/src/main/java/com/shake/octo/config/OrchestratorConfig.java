package com.shake.octo.config;

import com.shake.octo.gateway.ConversationId;
import com.shake.octo.gateway.OutboundReply;
import com.shake.octo.orchestrator.ExecutionPlan;
import com.shake.octo.orchestrator.Orchestrator;
import com.shake.octo.orchestrator.PlanExecutor;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.BiFunction;

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

    private ToolCallback executePlanTool(PlanExecutor planExecutor, ApplicationEventPublisher events)
    {
        String description = """
                Execute a plan of subagent tasks. Independent tasks run in parallel; \
                tasks listing other tasks in their `dependencies` wait for those to \
                finish and receive their outputs as context. Returns the labeled \
                results of the leaf tasks.
                
                Available subagents (use these exact names as `agentName`):
                <available_subagents>%s</available_subagents>
                """.formatted(planExecutor.listAgents());

        BiFunction<ExecutionPlan, ToolContext, String> execute = (plan, toolContext) -> {
            if (toolContext.getContext().get(Orchestrator.CTX_CONVERSATION) instanceof ConversationId to)
            {
                events.publishEvent(new OutboundReply(to, renderPlan(plan)));
            }
            return planExecutor.execute(plan);
        };

        return FunctionToolCallback.builder("executePlan", execute)
                                   .description(description)
                                   .inputType(ExecutionPlan.class)
                                   .build();
    }

    private static String renderPlan(ExecutionPlan plan)
    {
        return plan.executionSummary();
    }

    @Bean
    ChatClient orchestratorChatClient(ChatClient.Builder builder,
                                      @Value("classpath:prompts/orchestrator.md") Resource systemPrompt,
                                      ChatMemory chatMemory,
                                      PlanExecutor planExecutor,
                                      ApplicationEventPublisher events)
    {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultTools(executePlanTool(planExecutor, events))
                .defaultSystem(systemPrompt)
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
