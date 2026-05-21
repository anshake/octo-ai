package com.shake.octo.gateway;

import java.time.Instant;

public record InboundMessage(ConversationId from, String text, Instant receivedAt) {
}