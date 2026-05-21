package com.shake.assistant.orchestrator;

import com.shake.assistant.gateway.InboundMessage;
import com.shake.assistant.gateway.OutboundReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Orchestrator
{

    private final ChatClient chatClient;
    private final ApplicationEventPublisher events;

    @Async
    @EventListener
    public void onInbound(InboundMessage inbound)
    {
        log.info("Handling message from {}", inbound.from());
        String conversationId = inbound.from().externalId();
        try
        {
            String reply = call(conversationId, inbound.text());
            events.publishEvent(new OutboundReply(inbound.from(), reply));
        }
        catch (Exception e)
        {
            events.publishEvent(new OutboundReply(inbound.from(), e.getMessage()));
        }
    }

    private String call(String conversationId, String text)
    {
        return chatClient.prompt()
                         .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                         .user(text)
                         .call()
                         .content();
    }

}