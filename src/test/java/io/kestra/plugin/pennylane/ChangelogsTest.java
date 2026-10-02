package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.validation.ConstraintViolationException;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.pennylane.changelogs.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ChangelogsTest {

    private static WireMockServer wireMockServer;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    private String getBaseUrl() {
        return wireMockServer.baseUrl() + "/api/external/v2";
    }

    @Test
    void testChangelogListMapsV2Item() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .withQueryParam("start_date", equalTo("2024-01-01T00:00:00Z"))
            .withQueryParam("cursor", absent())
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "id": 901,
                                "operation": "insert",
                                "processed_at": "2024-01-01T12:00:00Z",
                                "updated_at": "2024-01-01T12:00:00Z",
                                "created_at": "2024-01-01T12:00:00Z"
                            },
                            {
                                "id": 902,
                                "operation": "update",
                                "processed_at": "2024-01-01T15:30:00Z",
                                "updated_at": "2024-01-01T15:30:00Z",
                                "created_at": "2024-01-01T08:00:00Z"
                            }
                        ]
                    }
                    """)));

        var task = List.builder()
            .id("test-changelogs-list-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .resource(Property.ofValue(List.ChangelogResource.customer_invoices))
            .since(Property.ofValue("2024-01-01T00:00:00Z"))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(rc);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getOperation(), is("insert"));
        assertThat(output.getRows().get(0).getId(), is(901L));
        assertThat(output.getRows().get(0).getProcessedAt(), is("2024-01-01T12:00:00Z"));
        assertThat(output.getRows().get(1).getOperation(), is("update"));
        assertThat(output.getRows().get(1).getId(), is(902L));
    }

    @Test
    void testChangelogSecondPageOmitsStartDate() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .withQueryParam("start_date", equalTo("2024-01-01T00:00:00Z"))
            .withQueryParam("cursor", absent())
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": true,
                        "next_cursor": "cur-2",
                        "items": [
                            {"id": 1, "operation": "insert", "processed_at": "2024-01-01T10:00:00Z"}
                        ]
                    }
                    """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .withQueryParam("cursor", equalTo("cur-2"))
            .withQueryParam("start_date", absent())
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {"id": 2, "operation": "update", "processed_at": "2024-01-01T11:00:00Z"}
                        ]
                    }
                    """)));

        var task = List.builder()
            .id("test-changelogs-page-2-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .resource(Property.ofValue(List.ChangelogResource.supplier_invoices))
            .since(Property.ofValue("2024-01-01T00:00:00Z"))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(rc);

        assertThat(output.getCount(), is(2));
        assertThat(output.getRows().get(1).getId(), is(2L));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/api/external/v2/changelogs/supplier_invoices"))
            .withQueryParam("cursor", equalTo("cur-2"))
            .withQueryParam("start_date", absent()));
    }

    @Test
    void testChangelogPageSizeOutOfRange() {
        var task = List.builder()
            .id("test-changelogs-page-size-" + IdUtils.create())
            .type(List.class.getName())
            .apiToken(Property.ofValue("token"))
            .baseUrl(Property.ofValue(getBaseUrl()))
            .resource(Property.ofValue(List.ChangelogResource.customers))
            .pageSize(Property.ofValue(1001))
            .build();

        RunContext rc = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var ex = assertThrows(ConstraintViolationException.class, () -> task.run(rc));
        assertThat(ex.getMessage(), containsString("pageSize"));
    }
}
