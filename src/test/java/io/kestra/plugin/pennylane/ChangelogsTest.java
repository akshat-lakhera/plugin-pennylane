package io.kestra.plugin.pennylane;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
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
    void testChangelogList() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/external/v2/changelogs/customer_invoices"))
            .withQueryParam("start_date", equalTo("2024-01-01T00:00:00Z"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "has_more": false,
                        "next_cursor": null,
                        "items": [
                            {
                                "action": "created",
                                "resource_id": 901,
                                "happened_at": "2024-01-01T12:00:00Z"
                            },
                            {
                                "action": "updated",
                                "resource_id": 902,
                                "happened_at": "2024-01-01T15:30:00Z"
                            }
                        ]
                    }
                    """)));

        var task = List.builder()
            .id("test-changelogs-list")
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
        assertThat(output.getRows().get(0).getAction(), is("created"));
        assertThat(output.getRows().get(0).getResourceId(), is(901L));
        assertThat(output.getRows().get(1).getAction(), is("updated"));
        assertThat(output.getRows().get(1).getResourceId(), is(902L));
    }
}
