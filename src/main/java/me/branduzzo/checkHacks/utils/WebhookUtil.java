package me.branduzzo.checkHacks.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebhookUtil {

    private static final String TITLE = "Detekce";
    private static final int COMPONENTS_V2_FLAG = 1 << 15;
    private static final int MAX_TEXT_CHARS = 3900;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CheckHacks-Webhook");
        thread.setDaemon(true);
        return thread;
    });

    public static void sendResult(String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText) {
        sendResult(webhookUrl, color, messageTemplate, playerName, checkerName, reason,
                hacksChecked, resultText, "", false);
    }

    public static void sendResult(String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText, boolean useComponentsV2) {
        sendResult(webhookUrl, color, messageTemplate, playerName, checkerName, reason,
                hacksChecked, resultText, "", useComponentsV2);
    }

    public static void sendResult(String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText, String thumbnailUrl,
                                  boolean useComponentsV2) {
        if (!isValid(webhookUrl)) return;
        String description = messageTemplate
                .replace("&name&", playerName)
                .replace("&checker&", checkerName)
                .replace("&reason&", reason)
                .replace("&hacks&", hacksChecked)
                .replace("&results&", resultText);
        sendRaw(webhookUrl, color, description, thumbnailUrl, useComponentsV2);
    }

    public static void sendRaw(String webhookUrl, int color, String description) {
        sendRaw(webhookUrl, color, description, "", false);
    }

    public static void sendRaw(String webhookUrl, int color, String description, boolean useComponentsV2) {
        sendRaw(webhookUrl, color, description, "", useComponentsV2);
    }

    public static void sendRaw(String webhookUrl, int color, String description,
                               String thumbnailUrl, boolean useComponentsV2) {
        if (!isValid(webhookUrl)) return;
        String body = useComponentsV2
                ? buildComponentsV2(color, description, thumbnailUrl)
                : buildEmbed(color, description, thumbnailUrl);
        sendJson(configureQuery(webhookUrl, useComponentsV2), body);
    }

    private static String buildEmbed(int color, String description, String thumbnailUrl) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", TITLE);
        embed.addProperty("description", description == null ? "" : description);
        embed.addProperty("color", color);
        if (hasThumbnail(thumbnailUrl)) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", thumbnailUrl.trim());
            embed.add("thumbnail", thumbnail);
        }
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject root = new JsonObject();
        root.add("embeds", embeds);
        return root.toString();
    }

    private static String buildComponentsV2(int color, String description, String thumbnailUrl) {
        String content = description == null || description.isBlank() ? "No data" : description;
        List<String> chunks = chunks("## " + TITLE + "\n" + content);

        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.addProperty("accent_color", color);
        JsonArray components = new JsonArray();
        for (int i = 0; i < chunks.size(); i++) {
            JsonObject text = textComponent(chunks.get(i));
            if (i == 0 && hasThumbnail(thumbnailUrl)) {
                JsonObject section = new JsonObject();
                section.addProperty("type", 9);
                JsonArray sectionComponents = new JsonArray();
                sectionComponents.add(text);
                section.add("components", sectionComponents);
                JsonObject media = new JsonObject();
                media.addProperty("url", thumbnailUrl.trim());
                JsonObject accessory = new JsonObject();
                accessory.addProperty("type", 11);
                accessory.add("media", media);
                section.add("accessory", accessory);
                components.add(section);
            } else {
                components.add(text);
            }
        }
        JsonObject separator = new JsonObject();
        separator.addProperty("type", 14);
        separator.addProperty("divider", true);
        separator.addProperty("spacing", 1);
        components.add(separator);
        components.add(textComponent("-# <t:" + Instant.now().getEpochSecond() + ":F>"));
        container.add("components", components);
        JsonArray top = new JsonArray();
        top.add(container);
        JsonObject root = new JsonObject();
        root.addProperty("flags", COMPONENTS_V2_FLAG);
        root.add("components", top);
        return root.toString();
    }

    private static JsonObject textComponent(String content) {
        JsonObject text = new JsonObject();
        text.addProperty("type", 10);
        text.addProperty("content", content);
        return text;
    }

    private static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.length() > 36000 ? text.substring(0, 36000) : text;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= MAX_TEXT_CHARS) {
                chunks.add(remaining);
                break;
            }
            int cut = remaining.lastIndexOf('\n', MAX_TEXT_CHARS);
            if (cut < MAX_TEXT_CHARS / 2) cut = MAX_TEXT_CHARS;
            chunks.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut);
            if (remaining.startsWith("\n")) remaining = remaining.substring(1);
        }
        if (chunks.isEmpty()) chunks.add("No data");
        return chunks;
    }

    private static boolean hasThumbnail(String url) {
        return url != null && !url.isBlank();
    }

    private static boolean isValid(String url) {
        return url != null && !url.isBlank() && !url.contains("CHANGE_ME")
                && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /**
     * Adds wait=true and sets with_components=true only while Components V2 is enabled
     * (removes it otherwise). Other query parameters and the fragment are preserved.
     */
    private static String configureQuery(String webhookUrl, boolean useComponentsV2) {
        int fragmentIndex = webhookUrl.indexOf('#');
        String fragment = fragmentIndex >= 0 ? webhookUrl.substring(fragmentIndex) : "";
        String urlWithoutFragment = fragmentIndex >= 0 ? webhookUrl.substring(0, fragmentIndex) : webhookUrl;

        int queryIndex = urlWithoutFragment.indexOf('?');
        String baseUrl = queryIndex >= 0 ? urlWithoutFragment.substring(0, queryIndex) : urlWithoutFragment;
        String query = queryIndex >= 0 ? urlWithoutFragment.substring(queryIndex + 1) : "";

        StringBuilder updatedQuery = new StringBuilder();
        for (String parameter : query.split("&")) {
            if (parameter.isBlank()) continue;
            String key = parameter.split("=", 2)[0];
            if (key.equalsIgnoreCase("with_components") || key.equalsIgnoreCase("wait")) continue;
            if (!updatedQuery.isEmpty()) updatedQuery.append('&');
            updatedQuery.append(parameter);
        }
        if (!updatedQuery.isEmpty()) updatedQuery.append('&');
        updatedQuery.append("wait=true");
        if (useComponentsV2) updatedQuery.append("&with_components=true");

        return baseUrl + "?" + updatedQuery + fragment;
    }

    private static void sendJson(String webhookUrl, String json) {
        EXECUTOR.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("User-Agent", "CheckHacks/1.3.2")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.err.println("[CheckHacks] Webhook HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                System.err.println("[CheckHacks] Webhook error: " + e.getMessage());
            }
        });
    }
}
