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
 * Auto-translates a completed transcript's segments.
 *
 * <p>This class provides translation capabilities for transcription results,
 * allowing users to translate transcripts into different languages. It uses a
 * LibreTranslate-compatible REST API endpoint.</p>
 *
 * <p><b>Why an HTTP endpoint, not a bundled library or a specific paid API:</b>
 * Deliberately kept generic and pluggable rather than hardcoding a dependency
 * on a specific paid service (Google Translate, DeepL) or bundling a
 * heavyweight offline translation model. This speaks the widely-implemented
 * LibreTranslate-compatible REST contract which can be self-hosted or pointed
 * at various compatible services.</p>
 *
 * <p><b>Privacy note:</b> The app never phones home without being asked.
 * Translation is opt-in per use, and the endpoint is whatever the user
 * configures — not a hardcoded third-party service.</p>
 *
 * @author AudioManager Project Contributors
 * @version 4.0.0
 * @see TranscriptionResult
 * @see TranscriptionException
 */
public class TranslationService {

    private final String endpointUrl;
    private final String apiKey;      // nullable — some self-hosted instances don't require one
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /**
     * Constructs a new TranslationService.
     *
     * @param endpointUrl the URL of the translation endpoint (LibreTranslate-compatible)
     * @param apiKey the API key for the translation service (may be {@code null})
     */
    public TranslationService(String endpointUrl, String apiKey) {
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Translates every segment's text in the result to the target language.
     *
     * <p>This method returns a new {@link TranscriptionResult} with translated
     * text — the original is left untouched, so callers decide whether to
     * save the translation alongside or instead of the original.</p>
     *
     * @param result the transcription result to translate
     * @param targetLanguageCode the target language code (ISO 639-1, e.g., "es", "fr", "de")
     * @return a new {@link TranscriptionResult} with translated segments
     * @throws TranscriptionException if the endpoint is unreachable, returns an error,
     *         or the response can't be parsed
     */
    public TranscriptionResult translateSegments(TranscriptionResult result, String targetLanguageCode)
            throws TranscriptionException {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new TranscriptionException(
                    "No translation endpoint configured",
                    "Translation isn't configured. Set a translation endpoint in Preferences first.",
                    null, false);
        }

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

        return new TranscriptionResult(translatedFullText.toString(), targetLanguageCode,
                result.getDuration(), translated);
    }

    /**
     * Translates a single string.
     *
     * <p>This method is exposed separately for use cases where only a single
     * string needs translation (e.g., a title or summary rather than a whole
     * transcript).</p>
     *
     * @param text the text to translate
     * @param targetLanguageCode the target language code (ISO 639-1)
     * @param sourceLanguageCode the source language code (ISO 639-1), or "auto" for auto-detection
     * @return the translated text
     * @throws TranscriptionException if the translation fails
     */
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

    /**
     * Truncates a string to 300 characters for error messages.
     */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}