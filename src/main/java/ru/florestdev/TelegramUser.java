package ru.florestdev;

public record TelegramUser(
        long id,
        String firstName,
        String lastName,
        String username,
        boolean isBot,
        String status // member, administrator, creator, left, kicked
) {}