package meko.agentic.sre.config;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenRouter gets a `reasoning` object, not OpenAI's `reasoning_effort`.
 *
 * This is the test the openrouter profile most needs. OpenRouter normalises reasoning across the
 * providers it routes to and reads `{"reasoning": {"effort": "high"}}`; it does not reject
 * `reasoning_effort`, it ignores it. Had the Groq converter been reused here, both 120b tiers
 * would run at the router's default effort — the frontier tier would silently BE the llm tier,
 * and nothing in the unit tests, the app's behaviour or the report would have said so.
 *
 * A local stub rather than OpenRouter itself: no key, no spend, and the assertion is about the
 * request we send, which is entirely ours to check.
 */
class OpenRouterRequestShapeTest {

    private static final String COMPLETION_RESPONSE = """
            {"id":"chatcmpl-stub","object":"chat.completion","created":1,
             "model":"openai/gpt-oss-120b",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            var body = COMPLETION_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Configuration
    @EnableConfigurationProperties({OpenRouterProperties.class, TokenProperties.class})
    static class Harness {
    }

    @Test
    void theFrontierTierSendsAReasoningObjectAtHighEffort() {
        callTier(OpenRouterModelsConfig.FRONTIER_NAME);

        var body = lastRequestBody.get();
        assertNotNull(body, "the stub was never called");
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-120b\""), body);
        assertTrue(normalise(body).contains("\"reasoning\":{\"effort\":\"high\"}"),
                "OpenRouter reads a reasoning object and this is what separates the tiers: " + body);
    }

    @Test
    void theLlmTierSendsTheSameModelAtLowEffort() {
        callTier(OpenRouterModelsConfig.LLM_NAME);

        var body = lastRequestBody.get();
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-120b\""), body);
        assertTrue(normalise(body).contains("\"reasoning\":{\"effort\":\"low\"}"), body);
    }

    /**
     * The shape Groq needs would be accepted and ignored here, which is the whole hazard. If this
     * ever starts failing, someone has pointed the OpenRouter tiers at the EFFORT_FIELD style and
     * the frontier tier has stopped being a frontier tier.
     */
    @Test
    void theOpenAiStyleEffortFieldIsNotSentToOpenRouter() {
        callTier(OpenRouterModelsConfig.FRONTIER_NAME);

        assertFalse(lastRequestBody.get().contains("reasoning_effort"),
                "OpenRouter ignores reasoning_effort; sending it instead of the object would "
                        + "collapse the tiers invisibly: " + lastRequestBody.get());
    }

    @Test
    void theSmallTierSendsTheSmallModel() {
        callTier(OpenRouterModelsConfig.SLM_NAME);

        assertTrue(lastRequestBody.get().contains("\"model\":\"openai/gpt-oss-20b\""),
                lastRequestBody.get());
    }

    /** The tier handle is an Embabel-side name and must never appear in a request. */
    @Test
    void theRegisteredTierNameIsNeverSentToTheProvider() {
        callTier(OpenRouterModelsConfig.FRONTIER_NAME);

        assertFalse(lastRequestBody.get().contains(OpenRouterModelsConfig.FRONTIER_NAME),
                "the tier handle leaked onto the wire: " + lastRequestBody.get());
    }

    /** Whitespace between JSON tokens is the serialiser's business, not this test's. */
    private static String normalise(String json) {
        return json.replaceAll("\\s+", "");
    }

    private void callTier(String tierName) {
        new ApplicationContextRunner()
                .withInitializer(ctx ->
                        ctx.getEnvironment().setActiveProfiles(OpenRouterModelsConfig.PROFILE))
                .withUserConfiguration(Harness.class, OpenRouterModelsConfig.class)
                .withPropertyValues(
                        "openrouter.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "openrouter.api-key=sk-or-stub")
                .run(context -> {
                    var tier = (SpringAiLlmService) context.getBeansOfType(LlmService.class)
                            .values().stream()
                            .filter(s -> tierName.equals(s.getName()))
                            .findFirst()
                            .orElseThrow();

                    var options = tier.convertOptions(LlmOptions.withDefaultLlm());
                    tier.getChatModel().call(new Prompt("ping", options));
                });
    }
}
