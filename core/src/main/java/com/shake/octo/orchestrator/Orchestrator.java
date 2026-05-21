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

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class Orchestrator
{

    public static final String CTX_CONVERSATION = "conversationId";

    private final ChatClient chatClient;
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
        return chatClient.prompt()
                         .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.externalId()))
                         .toolContext(Map.of(CTX_CONVERSATION, conversationId))
                         .user(text)
                         .call()
                         .content();
    }

}