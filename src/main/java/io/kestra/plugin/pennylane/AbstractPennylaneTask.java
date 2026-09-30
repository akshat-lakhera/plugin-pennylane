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
import io.kestra.plugin.pennylane.models.Changelog;
import io.kestra.plugin.pennylane.models.PennylaneOffsetPage;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_LIST_PAGE_SIZE = 100;
    public static final int MAX_CHANGELOG_PAGE_SIZE = 1000;
    public static final int MAX_TRIAL_BALANCE_PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 1000;
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 30000L;

    public static final ObjectMapper MAPPER = JacksonMapper.ofJson(false)
        .copy()
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);

    public enum PageMode {
        STANDARD,
        CHANGELOG
    }

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

    public static int renderPageSize(RunContext runContext, Property<Integer> pageSize, int maximum) throws IllegalVariableEvaluationException {
        int rPageSize = pageSize == null
            ? DEFAULT_PAGE_SIZE
            : runContext.render(pageSize).as(Integer.class).orElse(DEFAULT_PAGE_SIZE);
        if (rPageSize < 1 || rPageSize > maximum) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + maximum + " but was " + rPageSize);
        }
        return rPageSize;
    }

    public static Integer renderMaxRecords(RunContext runContext, Property<Integer> maxRecords) throws IllegalVariableEvaluationException {
        if (maxRecords == null) {
            return null;
        }
        var rendered = runContext.render(maxRecords).as(Integer.class);
        if (rendered.isEmpty()) {
            return null;
        }
        int rMaxRecords = rendered.get();
        if (rMaxRecords < 1) {
            throw new IllegalArgumentException("maxRecords must be greater than or equal to 1 but was " + rMaxRecords);
        }
        return rMaxRecords;
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

    public static <T> T fetchById(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String path,
        Class<T> type
    ) {
        try {
            String url = join(baseUrl, path);
            return request(
                runContext,
                options,
                apiToken,
                HttpRequest.builder().uri(URI.create(url)).method("GET"),
                type
            ).getBody();
        } catch (Exception e) {
            runContext.logger().warn("Skipping Pennylane resource {} after fetch failed: {}", path, e.getMessage());
            return null;
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

    protected <T> FetchResult<T> drain(
        RunContext runContext,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        Property<FetchType> fetchType,
        Integer maxRecords,
        PageMode pageMode,
        Predicate<T> include
    ) throws Exception {
        FetchType resolved = fetchType == null
            ? FetchType.FETCH
            : runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        String rApiToken = renderApiToken(runContext);
        String rBaseUrl = renderBaseUrl(runContext);
        return collect(runContext, resolved, consumer -> forEachIncludedPage(
            runContext,
            this.options,
            rApiToken,
            rBaseUrl,
            endpointPath,
            queryParams,
            itemType,
            pageMode,
            include,
            maxRecords,
            consumer
        ));
    }

    protected <T> FetchResult<T> drainOffset(
        RunContext runContext,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        Property<FetchType> fetchType,
        Integer maxRecords,
        int perPage
    ) throws Exception {
        FetchType resolved = fetchType == null
            ? FetchType.FETCH
            : runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        String rApiToken = renderApiToken(runContext);
        String rBaseUrl = renderBaseUrl(runContext);
        return collect(runContext, resolved, consumer -> forEachOffsetPage(
            runContext,
            this.options,
            rApiToken,
            rBaseUrl,
            endpointPath,
            queryParams,
            itemType,
            perPage,
            maxRecords,
            consumer
        ));
    }

    public static <T> List<T> listAll(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        PageMode pageMode,
        Predicate<T> include
    ) throws Exception {
        List<T> items = new ArrayList<>();
        forEachIncludedPage(
            runContext,
            options,
            apiToken,
            baseUrl,
            endpointPath,
            queryParams,
            itemType,
            pageMode,
            include,
            null,
            items::addAll
        );
        return items;
    }

    public record ChangelogSync(List<Changelog> unseen, PennylaneWatermark.State next) {
    }

    public static ChangelogSync syncChangelogs(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String resource,
        PennylaneWatermark.State previous,
        String lookbackStart
    ) throws Exception {
        String startDate = previous != null && previous.processedAt() != null && !previous.processedAt().isBlank()
            ? previous.processedAt()
            : lookbackStart;
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("limit", String.valueOf(MAX_CHANGELOG_PAGE_SIZE));
        if (startDate != null && !startDate.isBlank()) {
            queryParams.put("start_date", startDate);
        }

        List<Changelog> fetched = listAll(
            runContext,
            options,
            apiToken,
            baseUrl,
            "changelogs/" + resource,
            queryParams,
            Changelog.class,
            PageMode.CHANGELOG,
            null
        );

        List<Changelog> unseen = new ArrayList<>();
        for (Changelog change : fetched) {
            if (!PennylaneWatermark.alreadySeen(change, previous)) {
                unseen.add(change);
            }
        }
        return new ChangelogSync(unseen, PennylaneWatermark.advance(fetched, previous, startDate));
    }

    private static <T> FetchResult<T> collect(
        RunContext runContext,
        FetchType type,
        Pager<T> pager
    ) throws Exception {
        Accumulator<T> acc = new Accumulator<>();
        java.io.File tempFile = null;
        BufferedWriter writer = null;
        try {
            if (type == FetchType.STORE) {
                tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8);
            }
            BufferedWriter storeWriter = writer;
            pager.page(page -> {
                if (page.isEmpty()) {
                    return;
                }
                if (acc.first == null) {
                    acc.first = page.getFirst();
                }
                acc.count += page.size();
                if (type == FetchType.FETCH) {
                    acc.rows.addAll(page);
                } else if (type == FetchType.STORE) {
                    FileSerde.writeAll(storeWriter, Flux.fromIterable(page)).block();
                }
            });
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        URI uri = type == FetchType.STORE ? runContext.storage().putFile(tempFile) : null;
        return switch (type) {
            case FETCH -> new FetchResult<>(acc.rows, null, null, acc.count);
            case FETCH_ONE -> new FetchResult<>(null, acc.first, null, acc.count);
            case STORE -> new FetchResult<>(null, null, uri, acc.count);
            case NONE -> new FetchResult<>(null, null, null, acc.count);
        };
    }

    private static <T> void forEachIncludedPage(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        PageMode pageMode,
        Predicate<T> include,
        Integer maxRecords,
        PageBatchConsumer<T> consumer
    ) throws Exception {
        Map<String, String> baseParams = queryParams != null ? queryParams : Map.of();
        String cursor = null;
        int pages = 0;
        int accepted = 0;
        JavaType pageType = MAPPER.getTypeFactory().constructParametricType(PennylanePage.class, itemType);

        while (true) {
            pages++;
            if (pages > MAX_PAGES) {
                throw new IllegalStateException("Pennylane pagination exceeded " + MAX_PAGES + " pages for " + endpointPath);
            }

            Map<String, String> requestParams = paramsForPage(baseParams, cursor, pageMode);
            String fullUrl = buildUriWithParams(baseUrl, endpointPath, requestParams);
            @SuppressWarnings("unchecked")
            PennylanePage<T> page = (PennylanePage<T>) request(
                runContext,
                options,
                apiToken,
                HttpRequest.builder().uri(URI.create(fullUrl)).method("GET"),
                pageType
            ).getBody();

            List<T> rawItems = page != null && page.getItems() != null ? page.getItems() : List.of();
            List<T> included = take(rawItems, include, maxRecords, accepted);
            accepted += included.size();
            if (!included.isEmpty()) {
                consumer.accept(included);
            }
            if (maxRecords != null && accepted >= maxRecords) {
                return;
            }
            if (page == null || !Boolean.TRUE.equals(page.getHasMore())
                || page.getNextCursor() == null || page.getNextCursor().isBlank()) {
                return;
            }
            if (page.getNextCursor().equals(cursor)) {
                throw new IllegalStateException("Pennylane pagination returned the same cursor twice for " + endpointPath);
            }
            cursor = page.getNextCursor();
        }
    }

    private static <T> void forEachOffsetPage(
        RunContext runContext,
        HttpConfiguration options,
        String apiToken,
        String baseUrl,
        String endpointPath,
        Map<String, String> queryParams,
        Class<T> itemType,
        int perPage,
        Integer maxRecords,
        PageBatchConsumer<T> consumer
    ) throws Exception {
        int pageNumber = 1;
        int accepted = 0;
        JavaType pageType = MAPPER.getTypeFactory().constructParametricType(PennylaneOffsetPage.class, itemType);

        while (pageNumber <= MAX_PAGES) {
            Map<String, String> requestParams = new LinkedHashMap<>();
            if (queryParams != null) {
                requestParams.putAll(queryParams);
            }
            requestParams.put("page", Integer.toString(pageNumber));
            requestParams.put("per_page", Integer.toString(perPage));

            String fullUrl = buildUriWithParams(baseUrl, endpointPath, requestParams);
            @SuppressWarnings("unchecked")
            PennylaneOffsetPage<T> page = (PennylaneOffsetPage<T>) request(
                runContext,
                options,
                apiToken,
                HttpRequest.builder().uri(URI.create(fullUrl)).method("GET"),
                pageType
            ).getBody();

            List<T> rawItems = page != null && page.getItems() != null ? page.getItems() : List.of();
            List<T> included = take(rawItems, null, maxRecords, accepted);
            accepted += included.size();
            if (!included.isEmpty()) {
                consumer.accept(included);
            }
            if (maxRecords != null && accepted >= maxRecords) {
                return;
            }

            Integer totalPages = page != null ? page.getTotalPages() : null;
            if (totalPages != null) {
                if (pageNumber >= totalPages) {
                    return;
                }
            } else if (rawItems.size() < perPage) {
                return;
            }
            pageNumber++;
        }
        throw new IllegalStateException("Pennylane offset pagination exceeded " + MAX_PAGES + " pages for " + endpointPath);
    }

    private static Map<String, String> paramsForPage(Map<String, String> baseParams, String cursor, PageMode pageMode) {
        Map<String, String> requestParams = new LinkedHashMap<>();
        if (pageMode == PageMode.CHANGELOG && cursor != null) {
            String limit = baseParams.get("limit");
            if (limit != null) {
                requestParams.put("limit", limit);
            }
            requestParams.put("cursor", cursor);
            return requestParams;
        }
        requestParams.putAll(baseParams);
        if (cursor != null) {
            requestParams.put("cursor", cursor);
        } else {
            requestParams.remove("cursor");
        }
        return requestParams;
    }

    private static <T> List<T> take(List<T> rawItems, Predicate<T> include, Integer maxRecords, int already) {
        List<T> included = new ArrayList<>();
        for (T item : rawItems) {
            if (include != null && !include.test(item)) {
                continue;
            }
            if (maxRecords != null && already + included.size() >= maxRecords) {
                break;
            }
            included.add(item);
        }
        return included;
    }

    @FunctionalInterface
    private interface PageBatchConsumer<T> {
        void accept(List<T> items) throws Exception;
    }

    @FunctionalInterface
    private interface Pager<T> {
        void page(PageBatchConsumer<T> consumer) throws Exception;
    }

    private static final class Accumulator<T> {
        private int count;
        private T first;
        private final List<T> rows = new ArrayList<>();
    }

    public record FetchResult<T>(List<T> rows, T row, URI uri, int count) {
    }
}
