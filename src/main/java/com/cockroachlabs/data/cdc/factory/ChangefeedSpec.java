package com.cockroachlabs.data.cdc.factory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ChangefeedSpec {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final String table;
    private final List<String> columns;
    private final String webhookUrl;
    private final Map<String, String> options;
    private final boolean skipTlsVerify;

    private ChangefeedSpec(Builder builder) {
        this.table = builder.table;
        this.columns = List.copyOf(builder.columns);
        this.webhookUrl = builder.webhookUrl;
        this.options = Map.copyOf(builder.options);
        this.skipTlsVerify = builder.skipTlsVerify;
    }

    public static Builder forTable(String table) {
        validateIdentifier(table, "table");
        return new Builder(table);
    }

    public String toSQL() {
        StringBuilder sql = new StringBuilder("CREATE CHANGEFEED FOR TABLE ");
        sql.append(table);

        if (!columns.isEmpty()) {
            sql.append("(");
            sql.append(String.join(", ", columns));
            sql.append(")");
        }

        sql.append(" INTO '");
        String sinkUrl = "webhook-" + webhookUrl;
        if (skipTlsVerify && !sinkUrl.contains("insecure_tls_skip_verify")) {
            sinkUrl += (sinkUrl.contains("?") ? "&" : "?") + "insecure_tls_skip_verify=true";
        }
        sql.append(escapeSqlString(sinkUrl));
        sql.append("'");

        if (!options.isEmpty()) {
            sql.append(" WITH ");
            List<String> optParts = new ArrayList<>();
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    optParts.add(entry.getKey());
                } else {
                    optParts.add(entry.getKey() + " = '" + escapeSqlString(entry.getValue()) + "'");
                }
            }
            sql.append(String.join(", ", optParts));
        }

        return sql.toString();
    }

    public String getTable() { return table; }

    public List<String> getColumns() { return columns; }

    public String getWebhookUrl() { return webhookUrl; }

    public Map<String, String> getOptions() { return options; }

    private static void validateIdentifier(String name, String label) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + label + ": '" + name + "' (must match [a-zA-Z_][a-zA-Z0-9_]*)");
        }
    }

    private static String escapeSqlString(String value) {
        return value.replace("'", "''");
    }

    public static final class Builder {

        private final String table;
        private final List<String> columns = new ArrayList<>();
        private String webhookUrl;
        private final Map<String, String> options = new LinkedHashMap<>();
        private boolean skipTlsVerify = false;

        private Builder(String table) {
            this.table = table;
        }

        public Builder columns(String... cols) {
            for (String col : cols) {
                validateIdentifier(col, "column");
            }
            this.columns.addAll(Arrays.asList(cols));
            return this;
        }

        public Builder webhookUrl(String url) {
            this.webhookUrl = url;
            return this;
        }

        public Builder withDiff() {
            options.put("diff", "");
            return this;
        }

        public Builder withUpdatedTimestamps() {
            options.put("updated", "");
            return this;
        }

        public Builder withResolved(Duration interval) {
            options.put("resolved", interval.getSeconds() + "s");
            return this;
        }

        public Builder withAuthHeader(String headerValue) {
            options.put("webhook_auth_header", headerValue);
            return this;
        }

        public Builder withFormat(String format) {
            options.put("format", format);
            return this;
        }

        public Builder withEnvelope(String envelope) {
            options.put("envelope", envelope);
            return this;
        }

        public Builder skipTlsVerify() {
            this.skipTlsVerify = true;
            return this;
        }

        public Builder option(String key, String value) {
            options.put(key, value);
            return this;
        }

        public ChangefeedSpec build() {
            if (webhookUrl == null || webhookUrl.isBlank()) {
                throw new IllegalStateException("webhookUrl is required");
            }
            return new ChangefeedSpec(this);
        }
    }
}
