package meko.agentic.goap.story;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The curated documents in {@code src/main/resources/kb/}: policies, a runbook, review standards and an ADR,
 * written by people rather than agents. For live Meko they are uploaded to the datapack's knowledge base through the
 * Meko app (MCP has no upload tool); the offline stand-in loads them from the classpath.
 * <p>
 * Each document's title starts with its key ({@code CARD-DISPUTE-07: ...}). Meko prefixes every chunk with the
 * document title, so a chunk retrieved on its own still says which document it came from.
 */
@Component
public class KnowledgeDocuments {

    public record Document(String filename, String key, String title, String markdown) {
        /**
         * One chunk per {@code ## } section, in the shape Meko's chunker produces for uploaded Markdown:
         * {@code Section: <title> > <heading>}, a blank line, then the section body.
         */
        public List<String> chunks() {
            String[] parts = markdown.split("\n(?=## )");
            List<String> chunks = new ArrayList<>();
            String intro = parts[0].replaceFirst("(?m)^# .*\\R?", "").strip();
            if (!intro.isEmpty()) chunks.add("Section: " + title + "\n\n" + intro);
            Arrays.stream(parts).skip(1).forEach(p -> {
                String heading = p.lines().findFirst().orElse("").replaceFirst("^## ", "");
                chunks.add("Section: " + title + " > " + heading + "\n\n" + p.lines().skip(1).collect(Collectors.joining("\n")).strip());
            });
            return chunks;
        }
    }

    private final List<Document> documents;

    public KnowledgeDocuments() {
        this.documents = load();
    }

    public List<Document> all() {
        return documents;
    }

    public boolean covers(String key) {
        return documents.stream().anyMatch(d -> d.key().equals(key));
    }

    private static List<Document> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:kb/*.md");
            List<Document> docs = new ArrayList<>();
            for (Resource resource : resources) {
                String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
                String title = markdown.lines().filter(l -> l.startsWith("# ")).findFirst().orElse(resource.getFilename()).replaceFirst("^# ", "");
                String key = title.contains(":") ? title.substring(0, title.indexOf(':')).strip() : title;
                docs.add(new Document(resource.getFilename(), key, title, markdown));
            }
            docs.sort(Comparator.comparing(Document::filename));
            return List.copyOf(docs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
