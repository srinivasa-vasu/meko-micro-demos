package meko.agentic.goap.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import org.jspecify.annotations.NonNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Spring AI {@link ChatModel} over any OpenAI-compatible {@code /chat/completions} endpoint (Groq, vLLM, llama.cpp,
 * LM Studio, OpenRouter...), or Ollama's native {@code /api/chat} for Ollama tiers so the context window can be
 * bounded. Plain text generation only, which is all the response writer needs. Records the provider-reported token
 * usage of every call.
 */
public class OpenAiCompatibleChatModel implements ChatModel {
    private final String tier;
    private final LlmTierProperties.Tier config;
    private final ModelCallLog log;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpenAiCompatibleChatModel(String tier, LlmTierProperties.Tier config, ModelCallLog log) {
        this.tier = tier;
        this.config = config;
        this.log = log;
    }

    @Override
    public @NonNull ChatResponse call(Prompt prompt) {
        String allText = prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
        long start = System.nanoTime();
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                String role = switch (message.getMessageType()) {
                    case SYSTEM -> "system";
                    case ASSISTANT -> "assistant";
                    default -> "user";
                };
                messages.add(Map.of("role", role, "content", message.getText() == null ? "" : message.getText()));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("messages", messages);
            String url;
            if (config.ollama()) {
                // Ollama's native API, because its OpenAI-compatible endpoint cannot set the context window.
                body.put("stream", false);
                body.put("keep_alive", "30m");
                body.put("options", Map.of("num_ctx", config.getNumCtx(), "temperature", 0.3, "num_predict", config.getMaxTokens()));
                url = stripSlash(config.getBaseUrl()).replaceFirst("/v1$", "") + "/api/chat";
            } else {
                body.put("temperature", 0.3);
                body.put("max_tokens", config.getMaxTokens());
                body.putAll(config.getExtraBody());
                url = stripSlash(config.getBaseUrl()) + "/chat/completions";
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + config.getApiKey());
            }
            config.getHeaders().forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) {
                throw new IllegalStateException(config.getProvider() + " rate limit reached (free tier): " + abbreviate(response.body()));
            }
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(config.getProvider() + " HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            JsonNode json = objectMapper.readTree(response.body());
            String text;
            Integer promptTokens;
            Integer completionTokens;
            if (config.ollama()) {
                text = json.path("message").path("content").asString("").strip();
                promptTokens = json.has("prompt_eval_count") ? json.path("prompt_eval_count").asInt() : null;
                completionTokens = json.has("eval_count") ? json.path("eval_count").asInt() : null;
            } else {
                text = json.path("choices").path(0).path("message").path("content").asString("").strip();
                promptTokens = json.path("usage").has("prompt_tokens") ? json.path("usage").path("prompt_tokens").asInt() : null;
                completionTokens = json.path("usage").has("completion_tokens") ? json.path("usage").path("completion_tokens").asInt() : null;
            }
            // Reasoning models can inline their reasoning in <think> tags; only the answer is wanted.
            text = text.replaceAll("(?s)<think>.*?</think>", "").strip();
            if (text.isEmpty()) throw new IllegalStateException(config.getModel() + " returned no answer text (output budget spent on reasoning?)");
            log.record(allText, new ModelCallLog.ModelCall(tier, config.getProvider(), config.getModel(), promptTokens, completionTokens,
                    config.usd(promptTokens, completionTokens), millis(start), true, null));
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model(config.getModel())
                    .usage(new DefaultUsage(promptTokens, completionTokens))
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
        } catch (Exception e) {
            log.record(allText, new ModelCallLog.ModelCall(tier, config.getProvider(), config.getModel(), null, null,
                    0, millis(start), false, e.getMessage()));
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
