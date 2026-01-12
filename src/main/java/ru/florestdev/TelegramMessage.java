package ru.florestdev;

public record TelegramMessage(
        String fromName,
        String userId,
        String chatId,
        String text,
        boolean isEdited,
        Integer threadId
) {}
