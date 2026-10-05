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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What actually goes on the wire, checked against a stub that speaks OpenAI's chat-completions
 * shape.
 *
 * This is the one claim the other tests cannot make. GroqTierConfigurationTest proves the
 * converter puts reasoning_effort on an OpenAiChatOptions object; it cannot prove Spring AI and
 * the openai-java SDK then serialise that field into the request body. If they silently dropped
 * it, every test would still pass, the app would run, and the llm and frontier tiers would be
 * the same call at the same effort — the exact failure that looks like success.
 *
 * A local stub rather than Groq: no key, no network, no spend, and the assertion is about the
 * request we send, which is entirely ours to check.
 */
class GroqRequestShapeTest {

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
    @EnableConfigurationProperties({GroqProperties.class, TokenProperties.class})
    static class Harness {
    }

    @Test
    void theFrontierTierSendsItsModelIdAndHighReasoningEffort() {
        callTier(GroqModelsConfig.FRONTIER_NAME);

        var body = lastRequestBody.get();
        assertNotNull(body, "the stub was never called");
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-120b\""),
                "the Groq model id must be on the wire, not the Embabel tier name: " + body);
        assertTrue(body.contains("\"reasoning_effort\":\"high\""),
                "reasoning_effort is what separates the tiers and it was not sent: " + body);
    }

    @Test
    void theLlmTierSendsTheSameModelAtLowEffort() {
        callTier(GroqModelsConfig.LLM_NAME);

        var body = lastRequestBody.get();
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-120b\""), body);
        assertTrue(body.contains("\"reasoning_effort\":\"low\""), body);
    }

    @Test
    void theSmallTierSendsTheSmallModel() {
        callTier(GroqModelsConfig.SLM_NAME);

        var body = lastRequestBody.get();
        assertTrue(body.contains("\"model\":\"openai/gpt-oss-20b\""), body);
    }

    /** The tier handle is an Embabel-side name and must never appear in a request. */
    @Test
    void theRegisteredTierNameIsNeverSentToTheProvider() {
        callTier(GroqModelsConfig.FRONTIER_NAME);

        assertTrue(!lastRequestBody.get().contains(GroqModelsConfig.FRONTIER_NAME),
                "the tier handle leaked onto the wire: " + lastRequestBody.get());
    }

    private void callTier(String tierName) {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(GroqModelsConfig.PROFILE))
                .withUserConfiguration(Harness.class, GroqModelsConfig.class)
                .withPropertyValues(
                        "groq.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "groq.api-key=gsk_stub")
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
