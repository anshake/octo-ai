package com.shake.assistant;

import com.shake.assistant.gateway.InboundMessage;
import com.shake.assistant.gateway.OutboundReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Temporary stand-in for the real orchestrator: echoes every inbound message straight back.
 * Replace with the Spring AI orchestrator once core is wired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EchoOrchestrator
{

    private final ApplicationEventPublisher events;

    @Async
    @EventListener
    public void onInbound(InboundMessage inbound)
    {
        log.info("Echoing message from {}: {}", inbound.from(), inbound.text());
        events.publishEvent(new OutboundReply(inbound.from(), "Accepted: " + inbound.text()));
    }

}