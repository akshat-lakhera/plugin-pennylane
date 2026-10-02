package io.kestra.plugin.pennylane;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.pennylane.models.Transaction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class TransactionModelTest {

    @Test
    void serializedTransactionDoesNotExposeFilterHelper() throws Exception {
        Transaction transaction = Transaction.builder().id(9001L).build();

        String json = JacksonMapper.ofJson().writeValueAsString(transaction);
        Map<?, ?> map = JacksonMapper.ofJson().readValue(json, Map.class);

        assertThat(map.containsKey("categorizedForFilter"), is(false));
        assertThat(map.get("id"), is(9001));
        assertThat(transaction.isCategorizedForFilter(), is(false));
    }
}
