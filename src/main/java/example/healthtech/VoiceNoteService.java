package example.healthtech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceNoteService {
    private final String collection;
    private final String baseUrl;
    private final String key;
    private final OpenAIClient ai;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public VoiceNoteService(@Value("${notes.collection}") String collection,
                            @Value("${notes.base-url}") String baseUrl) {
        this.collection = collection;
        this.baseUrl = baseUrl;
        this.key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        this.ai = OpenAIOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).maxRetries(3).build();
    }

    public record Note(String noteId, String appointmentId, String transcript, boolean reminderApproved) {}
    public record Search(String query, int limit) {}
    public record Saved(String noteId, String appointmentId, String notification) {}

    static String notification(boolean approved) {
        return approved ? "Your appointment reminder is ready. Please use your secure patient portal for details."
                        : "No patient notification scheduled.";
    }

    @PostMapping("/notes")
    public Saved save(@RequestBody Note note) throws Exception {
        if (note.noteId() == null || note.noteId().isBlank() || note.appointmentId() == null
                || note.appointmentId().isBlank() || note.transcript() == null || note.transcript().isBlank())
            throw new IllegalArgumentException("noteId, appointmentId and transcript are required");
        List<Float> embedding = embed(note.transcript());
        // A stable caller-supplied noteId keeps a retried upsert on the same record.
        call("/vector/upsert", Map.of("collection", collection, "vectors", List.of(Map.of(
                "id", note.noteId(), "embedding", embedding,
                "metadata", Map.of("appointmentId", note.appointmentId(), "transcript", note.transcript())))));
        return new Saved(note.noteId(), note.appointmentId(), notification(note.reminderApproved()));
    }

    @PostMapping("/notes/search")
    public JsonNode search(@RequestBody Search search) throws Exception {
        if (search.query() == null || search.query().isBlank() || search.limit() < 1 || search.limit() > 20)
            throw new IllegalArgumentException("query and limit (1-20) are required");
        return call("/vector/query", Map.of("collection", collection, "embedding", embed(search.query()),
                "top_k", search.limit(), "include_metadata", true));
    }

    private List<Float> embed(String input) {
        return ai.embeddings().create(EmbeddingCreateParams.builder().model("auto").input(input).build())
                .data().get(0).embedding();
    }

    private JsonNode call(String path, Object body) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                long delay;
                try { delay = Math.max(1, Long.parseLong(retryAfter)) * 1000; }
                catch (NumberFormatException ignored) { delay = 500L << attempt; }
                Thread.sleep(Math.min(delay, 30000));
                continue;
            }
            if (!envelope.path("ok").asBoolean(false))
                throw new ApiRejection(response.statusCode(), envelope.path("error").toString());
            if (response.statusCode() >= 500) throw new IllegalStateException("Upstream request failed");
            return envelope.path("data");
        }
        throw new IllegalStateException("Retry budget exhausted");
    }

    public static final class ApiRejection extends RuntimeException {
        final int status;
        ApiRejection(int status, String message) { super(message); this.status = status; }
    }

    @ExceptionHandler(ApiRejection.class)
    public ResponseEntity<Map<String, String>> rejection(ApiRejection error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }
}
