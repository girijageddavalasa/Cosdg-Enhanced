package com.example.dag.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OllamaClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";

    public static String generate(String prompt) throws Exception {
        JsonObject jsonBody = new JsonObject();
        // Leverages your active designated qwen engine version string seamlessly
        jsonBody.addProperty("model", "qwen2.5:1.5b");
        jsonBody.addProperty("prompt", prompt);
        jsonBody.addProperty("stream", false);
        // CRITICAL ENFORCEMENT: Enforces pure structured JSON arrays at model inference boundaries
        jsonBody.addProperty("format", "json");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama returned an unexpected status error: " + response.statusCode());
        }

        // Unwrap the response envelope payload cleanly to protect JSON parsing operations
        String rawBody = response.body();
        try {
            JsonObject wrapper = JsonParser.parseString(rawBody).getAsJsonObject();
            if (wrapper.has("response")) {
                return wrapper.get("response").getAsString();
            }
        } catch (Exception e) {
            return rawBody;
        }

        return rawBody;
    }
}
