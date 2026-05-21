package com.shake.octo.gateway;

public record OutboundReply(ConversationId to, String text) {
}