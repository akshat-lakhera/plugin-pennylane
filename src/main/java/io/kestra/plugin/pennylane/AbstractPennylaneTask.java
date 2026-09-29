package io.kestra.plugin.pennylane;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.pennylane.models.PennylanePage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared authentication, HTTP transport, rate-limiting backoff, and pagination logic for Pennylane tasks.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractPennylaneTask extends Task {

    public static final String DEFAULT_BASE_URL = "https://app.pennylane.com/api/external/v2";
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 30000L;

    public static final ObjectMapper MAPPER = JacksonMapper.ofJson(false)
        .copy()
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);

    @Schema(
        title = "Pennylane API token",
        description = "Company or firm API token used to authenticate against the Pennylane API. Store it securely as a Kestra secret."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "Pennylane API base URL",
        description = "Base endpoint URL for Pennylane API calls. Defaults to `" + DEFAULT_BASE_URL + "`."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client options",
        description = "Optional HTTP client configuration (timeouts, proxy, SSL) applied to every request."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    protected String renderApiToken(RunContext runContext) throws IllegalVariableEvaluationException {
        return renderApiToken(runContext, this.apiToken);
    }

    protected String renderBaseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return renderBaseUrl(runContext, this.baseUrl);
    }

    public static String renderApiToken(RunContext runContext, Property<String> apiToken) throws IllegalVariableEvaluationException {
        return runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("apiToken is required")
        );
    }

    public static String renderBaseUrl(RunContext runContext, Property<String> baseUrl) throws IllegalVariableEvaluationException {
        return runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
    }

    protected <RES> HttpResponse<RES> request(
        RunContext runContext,
        HttpRequest.HttpRequestBuilder requestBuilder,
        Class<RES> responseType
    ) throws Exception {
        return request(runContext, this.options, renderApiToken(runContext), requestBuilder, responseType);
    }

    protected <RES> HttpResponse<RES> request(
        RunContext runContext,
        HttpRequest.HttpRequestBuilder requestBuilder,
        JavaType responseType
    ) throws Exception {
        return request(runContext, this.options, renderApiToken(runContext), requestBuilder, responseType);
    }

    public static <RES> HttpResponse<RES> request(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        HttpRequest.HttpRequestBuilder requestBuilder,
        Class<RES> responseType
    ) throws Exception {
        JavaType javaType = MAPPER.constructType(responseType);
        return request(runContext, options, apiToken, requestBuilder, javaType);
    }

    public static <RES> HttpResponse<RES> request(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        HttpRequest.HttpRequestBuilder requestBuilder,
        JavaType responseType
    ) throws Exception {
        String token = apiToken.trim();
        String authHeader = token.startsWith("Bearer ") ? token : "Bearer " + token;

        var request = requestBuilder
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", authHeader)
            .build();

        var configBuilder = options != null ? options.toBuilder() : HttpConfiguration.builder();

        int attempt = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (true) {
            try (var client = new HttpClient(runContext, configBuilder.build())) {
                var response = client.request(request, String.class);
                var body = response.getBody();

                if (body == null || body.isBlank()) {
                    body = responseType.isContainerType() ? "[]" : "{}";
                }

                @SuppressWarnings("unchecked")
                RES parsedResponse = responseType.getRawClass() == String.class
                    ? (RES) response.getBody()
                    : MAPPER.readValue(body, responseType);

                return HttpResponse.<RES>builder()
                    .request(request)
                    .body(parsedResponse)
                    .headers(response.getHeaders())
                    .status(response.getStatus())
                    .build();
            } catch (HttpClientResponseException e) {
                var status = e.getResponse() != null && e.getResponse().getStatus() != null
                    ? e.getResponse().getStatus().getCode()
                    : -1;

                if (status == 429 && attempt < MAX_RETRIES) {
                    attempt++;
                    long delay = parseRetryAfter(e, backoffMs);
                    runContext.logger().warn(
                        "Pennylane rate limit (HTTP 429) hit, retrying in {} ms (attempt {}/{})",
                        delay, attempt, MAX_RETRIES
                    );
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for Pennylane rate limit retry", ie);
                    }
                    backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                    continue;
                }

                throw rewriteError(runContext.logger(), e);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse Pennylane API response: " + e.getMessage(), e);
            }
        }
    }

    private static long parseRetryAfter(HttpClientResponseException e, long defaultDelayMs) {
        if (e.getResponse() != null && e.getResponse().getHeaders() != null) {
            var headers = e.getResponse().getHeaders();
            var retryAfterHeader = headers.firstValue("Retry-After");
            if (retryAfterHeader.isPresent()) {
                try {
                    long seconds = Long.parseLong(retryAfterHeader.get().trim());
                    return Math.max(1000L, seconds * 1000L);
                } catch (NumberFormatException ignored) {
                    // Ignore and fall back to exponential backoff
                }
            }
        }
        return defaultDelayMs;
    }

    private static HttpClientResponseException rewriteError(Logger logger, HttpClientResponseException e) {
        var response = e.getResponse();
        var status = response != null && response.getStatus() != null ? response.getStatus().getCode() : -1;

        logger.debug("Pennylane API call failed with HTTP {}: {}", status, e.getMessage());

        if (status == 401 || status == 403) {
            return new HttpClientResponseException(
                "Pennylane API returned HTTP " + status + ": invalid or missing API token. Verify apiToken is a " +
                    "valid, active token generated in your Pennylane settings.",
                response, e
            );
        }

        if (status == 404) {
            return new HttpClientResponseException(
                "Pennylane API returned HTTP 404: resource not found. Verify baseUrl and the requested identifier.",
                response, e
            );
        }

        if (status == 429) {
            return new HttpClientResponseException(
                "Pennylane API returned HTTP 429: rate limit exceeded after retry attempts.",
                response, e
            );
        }

        return new HttpClientResponseException(
            "Pennylane API request failed with HTTP " + status + ": " + e.getMessage(),
            response, e
        );
    }

    public static String join(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    public static String buildUriWithParams(String baseUrl, String path, Map<String, String> params) {
        String fullUrl = join(baseUrl, path);
        if (params == null || params.isEmpty()) {
            return fullUrl;
        }

        StringBuilder sb = new StringBuilder(fullUrl);
        boolean first = !fullUrl.contains("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(first ? "?" : "&");
                first = false;
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                sb.append("=");
                sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    /**
     * Executes cursor-based pagination over a Pennylane list endpoint.
     */
    protected <T> List<T> paginate(
        RunContext runContext,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        Integer maxRecords
    ) throws Exception {
        List<T> allItems = new ArrayList<>();
        String currentCursor = null;
        String baseUrlStr = renderBaseUrl(runContext);
        JavaType pageType = MAPPER.getTypeFactory().constructParametricType(PennylanePage.class, itemType);

        Map<String, String> activeParams = queryParams != null ? new LinkedHashMap<>(queryParams) : new LinkedHashMap<>();

        while (true) {
            if (currentCursor != null && !currentCursor.isBlank()) {
                activeParams.put("cursor", currentCursor);
            } else {
                activeParams.remove("cursor");
            }

            String fullUrl = buildUriWithParams(baseUrlStr, endpointPath, activeParams);
            var requestBuilder = HttpRequest.builder()
                .uri(URI.create(fullUrl))
                .method("GET");

            @SuppressWarnings("unchecked")
            PennylanePage<T> page = (PennylanePage<T>) request(runContext, requestBuilder, pageType).getBody();

            if (page != null && page.getItems() != null && !page.getItems().isEmpty()) {
                for (T item : page.getItems()) {
                    allItems.add(item);
                    if (maxRecords != null && allItems.size() >= maxRecords) {
                        return allItems;
                    }
                }
            }

            if (page == null || !Boolean.TRUE.equals(page.getHasMore()) ||
                page.getNextCursor() == null || page.getNextCursor().isBlank()) {
                break;
            }

            currentCursor = page.getNextCursor();
        }

        return allItems;
    }

    /**
     * Handles FetchType processing for lists of items:
     * - FETCH: in-memory list
     * - FETCH_ONE: single item
     * - STORE: writes rows to .ion internal storage file
     * - NONE: only total count
     */
    protected <T> FetchResult<T> fetchOutput(
        RunContext runContext,
        Property<FetchType> fetchType,
        List<T> items
    ) throws Exception {
        var total = items.size();
        FetchType type = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        return switch (type) {
            case FETCH -> new FetchResult<>(items, null, null, total);
            case FETCH_ONE -> new FetchResult<>(null, items.isEmpty() ? null : items.get(0), null, total);
            case STORE -> new FetchResult<>(null, null, store(runContext, items), total);
            case NONE -> new FetchResult<>(null, null, null, total);
        };
    }

    private static <T> URI store(RunContext runContext, List<T> items) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (var writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
            FileSerde.writeAll(writer, Flux.fromIterable(items)).block();
        }

        return runContext.storage().putFile(tempFile);
    }

    public record FetchResult<T>(List<T> rows, T row, URI uri, int count) {
    }
}
