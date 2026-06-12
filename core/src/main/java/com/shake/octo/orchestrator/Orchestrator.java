package com.shake.octo.orchestrator;

import com.shake.octo.gateway.ConversationId;
import com.shake.octo.gateway.InboundMessage;
import com.shake.octo.gateway.OutboundReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Entry point for user messages. Listens for {@link com.shake.octo.gateway.InboundMessage} events,
 * forwards each to the LLM via {@link ChatClient}, and publishes the reply as an
 * {@link com.shake.octo.gateway.OutboundReply} event.
 *
 * <p>The LLM always produces an {@link ExecutionPlan} as structured output — it cannot answer
 * directly. The plan is executed by {@link PlanExecutor} and its result is relayed back.
 * Per-conversation memory is maintained via {@link org.springframework.ai.chat.memory.ChatMemory}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Orchestrator
{

    private final ChatClient orchestratorChatClient;
    private final PlanExecutor planExecutor;
    private final ApplicationEventPublisher events;

    @Async
    @EventListener
    public void onInbound(InboundMessage inbound)
    {
        log.info("Handling message from {}", inbound.from());
        ConversationId from = inbound.from();
        try
        {
            String reply = call(from, inbound.text());
            events.publishEvent(new OutboundReply(from, reply));
        }
        catch (Exception e)
        {
            log.error("Failed to handle message from {}", from, e);
            events.publishEvent(new OutboundReply(from, e.getMessage()));
        }
    }

    private String call(ConversationId conversationId, String text)
    {
        ExecutionPlan plan = orchestratorChatClient.prompt()
                                                   .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.externalId()))
                                                   .user(text)
                                                   .call()
                                                   .entity(ExecutionPlan.class);

        if (plan == null)
        {
            return "Sorry, I couldn't process that request.";
        }
        // No subagent fits: the model leaves agentTasks empty and explains in the summary.
        if (plan.agentTasks() == null || plan.agentTasks().isEmpty())
        {
            return StringUtils.hasText(plan.executionSummary())
                    ? plan.executionSummary()
                    : "No subagent can handle that request.";
        }

        // Let the user see the plan before it runs; the final reply follows from onInbound.
        if (StringUtils.hasText(plan.executionSummary()))
        {
            events.publishEvent(new OutboundReply(conversationId, plan.executionSummary()));
        }

        return planExecutor.execute(plan);
    }

}