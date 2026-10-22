package meko.agentic.goap.context;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A knowledge-base upload through Meko's MCP: ask for a presigned URL with the file's size, hash and first bytes, then
 * post the file there as a form, Meko's fields first and the file last.
 */
class MekoKnowledgeUploadTest {

    private HttpServer storage;
    private final List<String> received = new ArrayList<>();
    private int storageStatus = 204;

    /** A fake Meko that issues an upload URL pointing at a local stand-in for the storage bucket. */
    class FakeMeko extends MekoMcpClient {
        final List<Map<String, Object>> uploadRequests = new ArrayList<>();
        boolean alreadyUploaded;
        private final ObjectMapper json = new ObjectMapper();

        FakeMeko(MekoProperties p) {
            super(p);
        }

        @Override
        public JsonNode callTool(String tool, Map<String, Object> args) {
            return switch (tool) {
                case "knowledgebase_upload_document" -> {
                    uploadRequests.add(args);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("key", "kb/dp-1/policy.md");
                    fields.put("policy", "eyJleHBpcmF0aW9uIjoi");
                    fields.put("x-amz-signature", "abc123");
                    yield json.valueToTree(alreadyUploaded
                            ? Map.of("key", "kb/dp-1/policy.md", "already_uploaded", true, "url", "", "fields", Map.of())
                            : Map.of("key", "kb/dp-1/policy.md", "already_uploaded", false,
                                    "url", "http://localhost:" + storage.getAddress().getPort() + "/bucket", "fields", fields));
                }
                case "knowledgebase_document_status" -> json.valueToTree(Map.of("document_key", args.get("document_key"), "state", "indexed"));
                default -> json.valueToTree(Map.of());
            };
        }

        @Override
        public String datapack() {
            return "dp-1";
        }
    }

    private FakeMeko meko() throws Exception {
        storage = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        storage.createContext("/bucket", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Content-Type") + "\n" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(storageStatus, -1);
            exchange.close();
        });
        storage.start();
        MekoProperties p = new MekoProperties();
        p.setEnabled(true);
        p.setApiKey("key");
        p.setDatapackName("dp");
        return new FakeMeko(p);
    }

    @AfterEach
    void stop() {
        if (storage != null) storage.stop(0);
    }

    @Test
    void asksForAnUrlForExactlyTheseBytesThenPostsTheFileLast() throws Exception {
        FakeMeko meko = meko();
        byte[] bytes = "# CARD-DISPUTE-07: Failed capture\n\nA provisional refund may be approved.\n".getBytes(StandardCharsets.UTF_8);

        MekoMcpClient.Upload upload = meko.uploadKnowledgeDocument("conv-1", "org-knowledge:curator", "policy.md", bytes);

        assertThat(upload.key()).isEqualTo("kb/dp-1/policy.md");
        assertThat(upload.alreadyUploaded()).isFalse();
        // The request names exactly these bytes: their size, SHA-256 and (a short file) all of them as the head.
        Map<String, Object> asked = meko.uploadRequests.getFirst();
        assertThat(asked).containsEntry("filename", "policy.md").containsEntry("size", bytes.length).containsEntry("datapack_id", "dp-1")
                .containsEntry("content_hash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .containsEntry("content_head_base64", Base64.getEncoder().encodeToString(bytes));
        // The form: Meko's fields in the order given, then the file, named "file", last.
        String form = received.getFirst();
        assertThat(form).startsWith("multipart/form-data; boundary=");
        assertThat(form.indexOf("name=\"key\"")).isLessThan(form.indexOf("name=\"policy\""));
        assertThat(form.indexOf("name=\"policy\"")).isLessThan(form.indexOf("name=\"x-amz-signature\""));
        assertThat(form.indexOf("name=\"x-amz-signature\"")).isLessThan(form.indexOf("name=\"file\"; filename=\"policy.md\""));
        assertThat(form).contains("A provisional refund may be approved.");

        assertThat(meko.documentStatus("conv-1", "org-knowledge:curator", upload.key()).path("state").asString()).isEqualTo("indexed");
    }

    @Test
    void sendsOnlyTheFirst3072BytesAsTheHead() throws Exception {
        FakeMeko meko = meko();
        byte[] bytes = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
        meko.uploadKnowledgeDocument("conv-1", "a", "long.md", bytes);
        assertThat(Base64.getDecoder().decode((String) meko.uploadRequests.getFirst().get("content_head_base64"))).hasSize(3072);
    }

    @Test
    void identicalContentAlreadyInTheDatapackIsNotSentAgain() throws Exception {
        FakeMeko meko = meko();
        meko.alreadyUploaded = true;
        MekoMcpClient.Upload upload = meko.uploadKnowledgeDocument("conv-1", "a", "policy.md", "same".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.alreadyUploaded()).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    void aStorageRefusalIsReportedWithItsStatus() throws Exception {
        FakeMeko meko = meko();
        storageStatus = 400;
        assertThatThrownBy(() -> meko.uploadKnowledgeDocument("conv-1", "a", "policy.md", "text".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("HTTP 400");
    }
}
