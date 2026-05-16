package com.shake.assistant.gateway.telegram;

import com.shake.assistant.gateway.ConversationId;
import com.shake.assistant.gateway.InboundMessage;
import com.shake.assistant.gateway.OutboundReply;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramGateway implements LongPollingSingleThreadUpdateConsumer
{

    public static final String GATEWAY_ID = "telegram";

    private final TelegramProperties properties;
    private final ApplicationEventPublisher events;

    private TelegramBotsLongPollingApplication botsApplication;
    private TelegramClient client;
    private Set<Long> allowedUserIds;

    @PostConstruct
    void start() throws Exception
    {
        allowedUserIds = properties.allowedUserIds() == null
                ? Set.of()
                : properties.allowedUserIds().stream().collect(Collectors.toUnmodifiableSet());
        client = new OkHttpTelegramClient(properties.botToken());
        botsApplication = new TelegramBotsLongPollingApplication();
        botsApplication.registerBot(properties.botToken(), this);
        log.info("Telegram gateway started; {} allowed user id(s)", allowedUserIds.size());
    }

    @PreDestroy
    void stop() throws Exception
    {
        if (botsApplication != null)
        {
            botsApplication.close();
        }
    }

    @Override
    public void consume(Update update)
    {
        Message message = update.getMessage();
        if (message == null || !message.hasText())
        {
            return;
        }
        Long userId = message.getFrom() == null ? null : message.getFrom().getId();
        if (userId == null || !allowedUserIds.contains(userId))
        {
            log.debug("Dropping update from unauthorized user id={}", userId);
            return;
        }
        InboundMessage inbound = new InboundMessage(
                new ConversationId(GATEWAY_ID, String.valueOf(userId)),
                message.getText(),
                Instant.now()
        );
        events.publishEvent(inbound);
    }

    @EventListener
    public void onOutboundReply(OutboundReply reply)
    {
        if (!GATEWAY_ID.equals(reply.to().gatewayId()))
        {
            return;
        }
        // In 1:1 DMs Telegram's chat id equals the user's user id, so the value we stored as
        // userId in ConversationId.externalId is what the Bot API wants as chatId here.
        long chatId = Long.parseLong(reply.to().externalId());
        try
        {
            client.execute(SendMessage.builder().chatId(chatId).text(reply.text()).build());
        }
        catch (Exception e)
        {
            log.warn("Failed to send Telegram reply to chatId={}", chatId, e);
        }
    }
}