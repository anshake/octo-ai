package com.shake.assistant.gateway;

import java.time.Instant;

public record InboundMessage(ConversationId from, String text, Instant receivedAt) {
}