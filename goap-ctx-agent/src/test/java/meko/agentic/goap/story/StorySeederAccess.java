package meko.agentic.goap.story;

/** Test access to the seeder's one-line versions of the documents. */
public final class StorySeederAccess {
    private StorySeederAccess() {}

    public static String knowledge(String docKey) {
        return StorySeeder.KNOWLEDGE.stream().filter(k -> docKey.equals(k.docKey())).map(StorySeeder.Seed::text).findFirst().orElseThrow();
    }
}
