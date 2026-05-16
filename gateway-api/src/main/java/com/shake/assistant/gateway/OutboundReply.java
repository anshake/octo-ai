package com.shake.assistant.gateway;

public record OutboundReply(ConversationId to, String text) {
}