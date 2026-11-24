package com.cockroachlabs.data.multiregion;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifiersTest {

    @Test
    void unquotedNamesFoldToLowerCase() {
        assertThat(Identifiers.parse("Orders")).containsExactly("orders");
        assertThat(Identifiers.parse("App.Orders")).containsExactly("app", "orders");
    }

    @Test
    void quotedNamesAreKeptVerbatim() {
        assertThat(Identifiers.parse("\"Orders\"")).containsExactly("Orders");
        assertThat(Identifiers.parse("\"My.Schema\".\"Order \"\"Lines\"\"\""))
                .containsExactly("My.Schema", "Order \"Lines\"");
        assertThat(Identifiers.parse("app.\"Orders\"")).containsExactly("app", "Orders");
    }

    @Test
    void plainNamesAreNotQuoted() {
        assertThat(Identifiers.quote("orders", Set.of())).isEqualTo("orders");
        assertThat(Identifiers.quote("order_lines2", Set.of())).isEqualTo("order_lines2");
    }

    @Test
    void otherNamesAreQuoted() {
        assertThat(Identifiers.quote("Orders", Set.of())).isEqualTo("\"Orders\"");
        assertThat(Identifiers.quote("order-lines", Set.of())).isEqualTo("\"order-lines\"");
        assertThat(Identifiers.quote("say \"hi\"", Set.of())).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Identifiers.quote("user", Set.of("user"))).isEqualTo("\"user\"");
    }
}
