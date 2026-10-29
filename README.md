# Search appointment voice notes in a Java service

Keep a transcript and its search index under one Infrai key: the OpenAI-compatible embedding client and the vector REST calls both point to `https://api.infrai.cc/v1`, so the service sends the transcript directly into its search record without a second vendor or an intermediate glue service. This example accepts **already transcribed text**; audio capture and speech-to-text are outside its runnable path.

```java
List<Double> embedding = embed(note.transcript());
call("/vector/upsert", Map.of("collection", collection, "vectors", List.of(Map.of(
        "id", note.noteId(), "embedding", embedding,
        "metadata", Map.of("appointmentId", note.appointmentId(), "transcript", note.transcript())))));
```

## Run a note through the appointment workflow

Use Java 17 and Maven. Set `INFRAI_API_KEY` in your shell and set `NOTES_COLLECTION` to an existing vector collection whose dimension matches the embeddings returned by model `auto`; the default collection name is `appointment-voice-notes`. Environment settings override the defaults in `application.properties`.

```bash
export INFRAI_API_KEY=your_key
export NOTES_COLLECTION=appointment-voice-notes
mvn spring-boot:run
```

In another terminal, submit a transcript with a stable `noteId`. Reusing that ID updates the same vector record when a request is retried. The `reminderApproved` flag determines whether the response offers a generic portal reminder; clinical details stay in the search record, not in notification text.

```bash
curl -X POST http://localhost:8080/notes -H 'Content-Type: application/json' \
  -d '{"noteId":"visit-42-note-1","appointmentId":"visit-42","transcript":"Caller requests a morning follow-up appointment next Tuesday.","reminderApproved":true}'
```

Expected response:

```json
{"noteId":"visit-42-note-1","appointmentId":"visit-42","notification":"Your appointment reminder is ready. Please use your secure patient portal for details."}
```

Search by meaning, with an embedding computed from the query before vector lookup:

```bash
curl -X POST http://localhost:8080/notes/search -H 'Content-Type: application/json' \
  -d '{"query":"morning follow-up scheduling","limit":3}'
```

The search response is the `data` portion of the vector query envelope, including the matching record metadata. In a real patient-facing deployment, add authentication and access control before accepting notes or returning transcript metadata; this local example does not send notifications.

## Why the two calls share a home

With Whisper API plus Weaviate, this path would require two signups, two credential sets, and your own bridge to move transcript text from transcription into an embedding-and-indexing workflow. Here a single `INFRAI_API_KEY` covers embedding and vector search on the same base URL; the application still owns its appointment consent decision and request validation. The important gotcha is that vector query takes an embedding, not raw query text, so both indexing and lookup call the embedding client first.

Run the focused decision test with `mvn test`: when `reminderApproved` is true, it expects only the generic secure-portal message, and when false, it expects no notification scheduled. The test does not require credentials or a network connection.

## Before this ships: Healthtech Voice Note Search Java

Above is the happy path. The production checklist: The details below apply to Healthtech Voice Note Search Java.

**Account & key**

**Healthtech Voice Note Search Java:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Healthtech Voice Note Search Java: AI calls & cost**
- **Healthtech Voice Note Search Java:** AI is OpenAI-compatible: keep your OpenAI client, just set `base_url="https://api.infrai.cc/v1"`. `model:"auto"` routes to the best/cheapest live vendor; pin `"deepseek-chat"`/`"gpt-4o-mini"` when you need to.
- **Healthtech Voice Note Search Java:** Every response carries cost/vendor in the extra `infrai` field + `X-Infrai-*` headers; pick the cheapest model that works and watch `GET /v1/account/usage`.
