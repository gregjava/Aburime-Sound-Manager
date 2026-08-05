/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.exceptions.TranscriptionException;
import audiomanager.model.TranscriptionResult;
import audiomanager.model.TranscriptionSegment;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Auto-translates a completed transcript's segments — "Translation API
 * integration" from the commercial-competitiveness review, never
 * previously built.
 *
 * <h2>Why an HTTP endpoint, not a bundled library or a specific paid API</h2>
 * Deliberately kept generic and pluggable rather than hardcoding a
 * dependency on a specific paid service (Google Translate, DeepL) this
 * app's users may not have accounts for, or bundling a heavyweight offline
 * translation model this session has no way to validate the licensing/size
 * tradeoffs of. Instead this speaks the widely-implemented
 * LibreTranslate-compatible REST contract (self-hostable, several public
 * instances exist, and DeepL/Google-compatible shims for this exact
 * request shape exist too) — point {@link #TranslationService(String, String)}
 * at whichever compatible endpoint you have access to.
 *
 * <p>This keeps the app's core "never phones home without being asked"
 * policy intact: translation is opt-in per use (nothing calls this
 * automatically), and the endpoint is whatever the user configures — not a
 * hardcoded third-party service this app would otherwise be silently
 * sending transcript content to.</p>
 */
public class TranslationService {

    private final String endpointUrl; // e.g. "https://libretranslate.example.com/translate"
    private final String apiKey;      // nullable — some self-hosted instances don't require one
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public TranslationService(String endpointUrl, String apiKey) {
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Translates every segment's text in {@code result} to {@code targetLanguageCode}
     * (ISO 639-1, e.g. "es", "fr", "de") and returns a NEW {@link TranscriptionResult}
     * with translated text — the original is left untouched, so callers decide
     * whether to save the translation alongside or instead of the original.
     *
     * @throws TranscriptionException if the endpoint is unreachable, returns
     *         an error, or the response can't be parsed — using the existing
     *         typed exception hierarchy so callers (e.g. a REST API job, or
     *         batch UI code) can distinguish "translation failed" from other
     *         failure categories rather than catching a bare Exception.
     */
    public TranscriptionResult translateSegments(TranscriptionResult result, String targetLanguageCode)
            throws TranscriptionException {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new TranscriptionException(
                    "No translation endpoint configured",
                    "Translation isn't configured. Set a translation endpoint in Preferences first.",
                    null, false);
        }

        // FIX: TranscriptionResult.Segment doesn't exist — segments are
        // audiomanager.model.TranscriptionSegment, a top-level class, not a
        // nested one. Also, neither TranscriptionSegment nor
        // TranscriptionResult has a "with*" copy method — both are plain
        // immutable classes with a constructor and getters — so a translated
        // copy has to be built via their real constructors instead.
        List<TranscriptionSegment> segments = result.getSegments();
        List<TranscriptionSegment> translated = new java.util.ArrayList<>(segments.size());
        StringBuilder translatedFullText = new StringBuilder();

        for (TranscriptionSegment segment : segments) {
            String translatedText = translateText(segment.getText(), targetLanguageCode, result.getLanguage());
            translated.add(new TranscriptionSegment(
                    segment.getStart(), segment.getEnd(), translatedText,
                    segment.getConfidence(), segment.getSpeaker()));
            translatedFullText.append(translatedText);
        }

        // The returned result's top-level text/language now reflect the
        // translation too (not just the segments) — a caller reading
        // getText()/getLanguage() on the returned object should see the
        // translated content, not silently-stale original-language values.
        return new TranscriptionResult(translatedFullText.toString(), targetLanguageCode,
                result.getDuration(), translated);
    }

    /** Translates a single string. Exposed separately in case a caller wants to translate, e.g., just a title or summary rather than a whole transcript. */
    public String translateText(String text, String targetLanguageCode, String sourceLanguageCode)
            throws TranscriptionException {
        if (text == null || text.isBlank()) return text;

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("q", text);
        requestBody.addProperty("source", sourceLanguageCode != null ? sourceLanguageCode : "auto");
        requestBody.addProperty("target", targetLanguageCode.toLowerCase(Locale.ROOT));
        requestBody.addProperty("format", "text");
        if (apiKey != null && !apiKey.isBlank()) {
            requestBody.addProperty("api_key", apiKey);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TranscriptionException(
                        "Translation endpoint returned HTTP " + response.statusCode() + ": " + truncate(response.body()),
                        "Translation service returned an error (HTTP " + response.statusCode() + "). "
                                + "Check the endpoint URL and API key in Preferences.",
                        null, false);
            }
            JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
            if (responseJson == null || !responseJson.has("translatedText")) {
                throw new TranscriptionException(
                        "Unexpected translation response shape: " + truncate(response.body()),
                        "The translation service returned an unexpected response format. "
                                + "Confirm it implements the LibreTranslate-compatible /translate contract.",
                        null, false);
            }
            return responseJson.get("translatedText").getAsString();
        } catch (IOException e) {
            throw new TranscriptionException(
                    "Translation request failed: " + e.getMessage(),
                    "Couldn't reach the translation service. Check the endpoint URL and your network connection.",
                    null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException(
                    "Translation request interrupted", "Translation was cancelled.", null, e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}