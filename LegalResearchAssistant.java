import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PAKLAW AI — Mode 3: Legal Research Q&A (Java MVP)
 * ---------------------------------------------------
 * This is a minimal, runnable demonstration of the core mechanic behind
 * a citation-grounded legal research assistant:
 *
 *   1. RETRIEVE relevant law sections from a knowledge base (here: a small
 *      in-memory list, standing in for a real vector database).
 *   2. BUILD a prompt that forces the LLM to answer ONLY from that context
 *      and to cite Act + Section for every claim.
 *   3. CALL the LLM API.
 *   4. PRINT the answer alongside the exact sources used — or an explicit
 *      "no verified authority found" warning if nothing matched.
 *
 * This is intentionally simplified so you can see the full pipeline in
 * one file. See the "HOW TO UPGRADE THIS" notes at the bottom for what
 * changes in a real system.
 *
 * REQUIREMENTS
 *   - Java 17 or newer (uses records + text blocks)
 *   - An OpenAI API key set as an environment variable: OPENAI_API_KEY
 *
 * HOW TO RUN
 *   javac LegalResearchAssistant.java
 *   export OPENAI_API_KEY=sk-...           (Linux/macOS)
 *   set OPENAI_API_KEY=sk-...              (Windows cmd)
 *   java LegalResearchAssistant "What does Section 302 PPC deal with?"
 */
public class LegalResearchAssistant {

    // ------------------------------------------------------------------
    // STEP 0: The "knowledge base". In production this is a vector
    // database (pgvector, Pinecone, Chroma) populated by ingesting the
    // actual Pakistan Code text. Here it's a hardcoded list so the whole
    // pipeline runs without external infrastructure.
    // ------------------------------------------------------------------
    record LawEntry(String act, String section, String text, String sourceUrl) {}

    private static final List<LawEntry> KNOWLEDGE_BASE = List.of(
        new LawEntry(
            "Pakistan Penal Code, 1860",
            "Section 302",
            "Whoever commits qatl-e-amd shall be punished with death as qisas, "
                + "or with imprisonment for life or up to 25 years as ta'zir, "
                + "having regard to the facts and circumstances of the case, "
                + "if the proof in either of the forms specified in Section 304 is not available.",
            "https://pakistancode.gov.pk/english/"
        ),
        new LawEntry(
            "Code of Criminal Procedure, 1898",
            "Section 154",
            "Every information relating to the commission of a cognizable offence, "
                + "if given orally to an officer in charge of a police station, shall be "
                + "reduced to writing and read over to the informant, and shall be signed "
                + "by the person giving it. This is commonly known as the First Information Report (FIR).",
            "https://pakistancode.gov.pk/english/"
        ),
        new LawEntry(
            "Constitution of Pakistan, 1973",
            "Article 199",
            "Subject to the Constitution, a High Court may, on the application of any "
                + "aggrieved party, issue writs including mandamus, prohibition, certiorari, "
                + "quo warranto, and habeas corpus.",
            "https://pakistancode.gov.pk/english/"
        )
        // Add more LawEntry objects here as you ingest more sections.
        // At scale, this list becomes rows in a database instead.
    );

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            System.out.println("Usage: java LegalResearchAssistant \"<your legal question>\"");
            return;
        }
        String question = String.join(" ", args);

        // STEP 1: Retrieval
        List<LawEntry> matches = retrieveRelevant(question);

        // STEP 2: Build citation-grounded prompt
        String prompt = buildPrompt(question, matches);

        // STEP 3: Call the LLM
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: Set the OPENAI_API_KEY environment variable before running.");
            return;
        }

        String answer;
        try {
            answer = callLLM(prompt, apiKey);
        } catch (IOException e) {
            System.err.println("ERROR: Network/API call failed — " + e.getMessage());
            return;
        }

        // STEP 4: Output — answer + sources, always shown together
        System.out.println("=== PAKLAW AI — Legal Research Answer ===\n");
        System.out.println(answer);
        System.out.println("\n--- Sources Used ---");
        if (matches.isEmpty()) {
            System.out.println(
                "No verified authority found in the knowledge base. "
                + "Do not rely on this answer without further legal research."
            );
        } else {
            for (LawEntry e : matches) {
                System.out.printf("- %s, %s -> %s%n", e.act(), e.section(), e.sourceUrl());
            }
        }
    }

    /**
     * STEP 1 detail: naive keyword-overlap retrieval.
     * This is a stand-in for real semantic search (embeddings + cosine
     * similarity against a vector DB). It only matches literal word
     * overlap, so it will miss paraphrased questions — that's the main
     * limitation to fix first when upgrading this.
     */
    private static List<LawEntry> retrieveRelevant(String question) {
        String q = question.toLowerCase();
        List<LawEntry> results = new ArrayList<>();
        for (LawEntry entry : KNOWLEDGE_BASE) {
            String haystack = (entry.act() + " " + entry.section() + " " + entry.text()).toLowerCase();
            for (String word : q.split("\\W+")) {
                if (word.length() > 3 && haystack.contains(word)) {
                    results.add(entry);
                    break;
                }
            }
        }
        return results;
    }

    /**
     * STEP 2 detail: the prompt is the single most important part of this
     * system. It explicitly forbids the model from answering outside the
     * provided context — this is what prevents hallucinated law.
     */
    private static String buildPrompt(String question, List<LawEntry> matches) {
        StringBuilder context = new StringBuilder();
        if (matches.isEmpty()) {
            context.append("No relevant law sections were found in the knowledge base.");
        } else {
            for (LawEntry e : matches) {
                context.append(String.format("[%s, %s]: %s%n", e.act(), e.section(), e.text()));
            }
        }

        return """
            You are a legal research assistant for Pakistani law.
            Answer ONLY using the context below. Cite the Act and Section for every claim you make.
            If the context does not contain enough information to answer, say so explicitly.
            Do not invent sections, acts, or judgments that are not in the context.

            CONTEXT:
            %s

            QUESTION:
            %s
            """.formatted(context, question);
    }

    /**
     * STEP 3 detail: calls OpenAI's chat completion endpoint using Java's
     * built-in HttpClient (no external HTTP library needed).
     * Swap the URL/model/headers here to use Claude, Gemini, etc. instead.
     */
    private static String callLLM(String prompt, String apiKey) throws IOException, InterruptedException {
        String escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");

        String body = """
            {
              "model": "gpt-4o-mini",
              "messages": [{"role": "user", "content": "%s"}],
              "temperature": 0.2
            }
            """.formatted(escapedPrompt);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("API returned status " + response.statusCode() + ": " + response.body());
        }
        return extractContent(response.body());
    }

    /**
     * Minimal manual JSON field extraction to avoid pulling in an external
     * JSON library for this MVP. In real code, use org.json or Jackson —
     * regex-based JSON parsing is fragile and only acceptable here because
     * the response shape is simple and predictable.
     */
    private static String extractContent(String json) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"(.*?)\"\\s*}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        }
        return "Could not parse response: " + json;
    }
}

/*
 * HOW TO UPGRADE THIS TOWARD THE REAL PAKLAW AI SYSTEM
 * ------------------------------------------------------
 * 1. Replace retrieveRelevant() with real semantic search:
 *      - Generate an embedding for the question (OpenAI embeddings API
 *        or a local model).
 *      - Store law sections as embeddings in pgvector/Pinecone/Chroma.
 *      - Retrieve top-k by cosine similarity instead of keyword overlap.
 *
 * 2. Replace the hardcoded KNOWLEDGE_BASE with a real database table,
 *    populated by an ingestion pipeline that parses Pakistan Code PDFs
 *    (PDF parsing + OCR for scanned documents + citation extraction).
 *
 * 3. Replace regex JSON parsing with a proper library (org.json, Jackson,
 *    or Gson) once you add a build tool (Maven/Gradle) — regex parsing
 *    breaks silently if the API response format changes.
 *
 * 4. Add retry/backoff logic around callLLM() for transient network
 *    failures, and log every query + answer + sources for audit purposes
 *    (a legal tool must be auditable).
 *
 * 5. Never hardcode or print the API key. Consider a secrets manager
 *    instead of a plain environment variable for production deployment.
 */
