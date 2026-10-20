package com.cockroachlabs.data.continuum.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Cockroach Continuum surfaces.
 *
 * <pre>
 * cockroachdb.continuum.aegis.enabled = true        # expose an AegisClient bean
 * cockroachdb.continuum.aegis.endpoint =            # blank → SimulatedAegisClient
 * cockroachdb.continuum.aegis.token =
 * cockroachdb.continuum.agent.enabled = true        # expose CrdbAgent + ops tools
 * cockroachdb.continuum.agent.allow-destructive = false
 * </pre>
 */
@ConfigurationProperties("cockroachdb.continuum")
public class CockroachDBContinuumProperties {

    private Aegis aegis = new Aegis();
    private Agent agent = new Agent();

    public Aegis getAegis() {
        return aegis;
    }

    public void setAegis(Aegis aegis) {
        this.aegis = aegis;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public static class Aegis {

        private boolean enabled = true;
        private String endpoint = "";
        private String token = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class Agent {

        private boolean enabled = true;
        private boolean allowDestructive = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAllowDestructive() {
            return allowDestructive;
        }

        public void setAllowDestructive(boolean allowDestructive) {
            this.allowDestructive = allowDestructive;
        }
    }
}
