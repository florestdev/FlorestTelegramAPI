package ru.florestdev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TelegramBot {

    private final HttpClient client;
    private final String botToken;
    private final String userAgent = "FlorestTelegramAPI/1.0";
    private int lastUpdateId = 0;

    public TelegramBot(String botToken) {
        this.botToken = botToken;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // --- Основные методы отправки ---

    public CompletableFuture<Void> banUser(String chatId, String userId) {
        return post("banChatMember", String.format("chat_id=%s&user_id=%s", chatId, userId));
    }

    public CompletableFuture<Void> unbanUser(String chatId, String userId) {
        return post("unbanChatMember", String.format("chat_id=%s&user_id=%s", chatId, userId));
    }

    public CompletableFuture<Void> sendMessage(String chatId, String text) {
        String body = String.format("chat_id=%s&text=%s", chatId, encode(text));
        return post("sendMessage", body);
    }

    public CompletableFuture<TelegramUser> getChatMember(String chatId, String userId) {
        String body = String.format("chat_id=%s&user_id=%s", chatId, userId);
        String url = String.format("https://api.telegram.org/bot%s/getChatMember", botToken);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("User not found or bot not in chat. Code: " + response.statusCode());
                    }

                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!root.get("ok").getAsBoolean()) return null;

                    JsonObject result = root.getAsJsonObject("result");
                    JsonObject userJson = result.getAsJsonObject("user");

                    return new TelegramUser(
                            userJson.get("id").getAsLong(),
                            userJson.get("first_name").getAsString(),
                            userJson.has("last_name") ? userJson.get("last_name").getAsString() : "",
                            userJson.has("username") ? userJson.get("username").getAsString() : "none",
                            userJson.get("is_bot").getAsBoolean(),
                            result.get("status").getAsString()
                    );
                })
                .exceptionally(ex -> {
                    System.err.println("[TelegramAPI] Error getting member: " + ex.getMessage());
                    return null;
                });
    }

    public CompletableFuture<Void> setChatDescription(String chatId, String description) {
        String body = String.format("chat_id=%s&description=%s", chatId, encode(description));
        return post("setChatDescription", body);
    }

    public CompletableFuture<Void> restrictChat(String chatId, boolean canSendMessages) {
        String body = String.format("chat_id=%s&permissions={\"can_send_messages\":%b}", chatId, canSendMessages);
        return post("setChatPermissions", body);
    }

    // --- Методы получения сообщений ---

    /**
     * Получает новые сообщения.
     * @param targetChatId ID чата, сообщения из которого нужно обрабатывать.
     * @param targetThreadId ID темы (топика), если нужно. Передайте null, если темы не используются.
     */
    public CompletableFuture<List<TelegramMessage>> getNewMessages(String targetChatId, Integer targetThreadId) {
        String url = String.format("https://api.telegram.org/bot%s/getUpdates?offset=%d",
                botToken, (lastUpdateId + 1));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<TelegramMessage> messages = new ArrayList<>();

                    if (response.statusCode() != 200) return messages;

                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!root.get("ok").getAsBoolean()) return messages;

                    JsonArray updates = root.getAsJsonArray("result");
                    for (JsonElement el : updates) {
                        JsonObject upd = el.getAsJsonObject();
                        this.lastUpdateId = upd.get("update_id").getAsInt();

                        if (upd.has("edited_message")) {
                            handleEdited(upd.getAsJsonObject("edited_message"), messages, targetChatId);
                        } else if (upd.has("message")) {
                            handleMessage(upd.getAsJsonObject("message"), messages, targetChatId, targetThreadId);
                        }
                    }
                    return messages;
                })
                .exceptionally(ex -> {
                    System.err.println("[TelegramAPI] Failed to get updates: " + ex.getMessage());
                    return new ArrayList<>();
                });
    }

    private void handleEdited(JsonObject msg, List<TelegramMessage> out, String targetChatId) {
        if (!msg.has("text") || !msg.has("chat")) return;

        String chatId = msg.getAsJsonObject("chat").get("id").getAsString();
        if (!chatId.equals(targetChatId)) return;

        out.add(new TelegramMessage(
                msg.getAsJsonObject("from").get("first_name").getAsString(),
                msg.getAsJsonObject("from").get("id").getAsString(),
                chatId,
                msg.get("text").getAsString(),
                true,
                msg.has("message_thread_id") ? msg.get("message_thread_id").getAsInt() : null
        ));
    }

    private void handleMessage(JsonObject msg, List<TelegramMessage> out, String targetChatId, Integer targetThreadId) {
        if (!msg.has("text") || !msg.has("chat")) return;

        String chatId = msg.getAsJsonObject("chat").get("id").getAsString();
        if (!chatId.equals(targetChatId)) return;

        Integer msgThreadId = msg.has("message_thread_id") ? msg.get("message_thread_id").getAsInt() : null;

        // Фильтрация по теме, если она включена в конфиге плагина
        if (targetThreadId != null && targetThreadId > 0) {
            if (msgThreadId == null || !msgThreadId.equals(targetThreadId)) return;
        }

        out.add(new TelegramMessage(
                msg.getAsJsonObject("from").get("first_name").getAsString(),
                msg.getAsJsonObject("from").get("id").getAsString(),
                chatId,
                msg.get("text").getAsString(),
                false,
                msgThreadId
        ));
    }

    // --- Внутренняя логика ---

    private CompletableFuture<Void> post(String method, String requestBody) {
        String url = String.format("https://api.telegram.org/bot%s/%s", botToken, method);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(null);
                    } else {
                        return CompletableFuture.failedFuture(
                                new RuntimeException("Telegram API Error [" + method + "]: " + response.body())
                        );
                    }
                });
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}