package com.shake.octo.gateway.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Configuration for the Telegram gateway.
 *
 * @param botToken       bot token issued by @BotFather
 * @param allowedUserIds Telegram user IDs permitted to talk to the assistant. In 1:1 DMs Telegram's
 *                       {@code chat.id} equals the user's {@code user.id}, so the same numeric value
 *                       is what the Bot API expects as {@code chatId} when sending a reply.
 *                       Any inbound update whose user ID isn't in this list is dropped silently.
 */
@ConfigurationProperties(prefix = "gateway.telegram")
public record TelegramProperties(String botToken, Set<Long> allowedUserIds)
{
    public TelegramProperties {
        if (allowedUserIds == null)
        {
            allowedUserIds = Set.of();
        }
    }

}