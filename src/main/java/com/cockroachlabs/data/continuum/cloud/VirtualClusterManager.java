package com.cockroachlabs.data.continuum.cloud;

import com.cockroachlabs.data.continuum.cloud.CloudClient.VirtualCluster;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle of Cockroach virtual clusters over a SQL connection to a
 * Continuum host cluster, using the {@code CREATE / SHOW / DROP VIRTUAL CLUSTER}
 * statements. These require a Continuum-enabled cluster.
 *
 * <p>Virtual-cluster names are interpolated into DDL (identifiers cannot be bind
 * parameters), so {@link #create} / {@link #drop} validate the name against a
 * strict identifier pattern to prevent SQL injection.
 */
public class VirtualClusterManager {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final JdbcTemplate jdbc;

    public VirtualClusterManager(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static void validate(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "cloud: invalid virtual-cluster name '" + name
                            + "' (allowed: letters, digits, underscore; must not start with a digit)");
        }
    }

    /** Runs {@code CREATE VIRTUAL CLUSTER <name>}. */
    public void create(String name) {
        validate(name);
        jdbc.execute("CREATE VIRTUAL CLUSTER " + name);
    }

    /** Runs {@code DROP VIRTUAL CLUSTER <name>}. */
    public void drop(String name) {
        validate(name);
        jdbc.execute("DROP VIRTUAL CLUSTER " + name);
    }

    /** Runs {@code SHOW VIRTUAL CLUSTERS} and returns the rows. */
    public List<VirtualCluster> list() {
        return jdbc.query("SHOW VIRTUAL CLUSTERS",
                (rs, i) -> {
                    String id = rs.getString(1);
                    String name = rs.getString(2);
                    return new VirtualCluster(name, id, name, null);
                });
    }

    /**
     * Rewrites a connection string to route connections to the named virtual
     * cluster. CockroachDB routes via the connection option {@code -ccluster=<name>};
     * this appends an {@code options} parameter carrying it. Assumes the base
     * string does not already set {@code options}.
     */
    public static String routingConnString(String base, String vclusterName) {
        validate(vclusterName);
        String sep = base.contains("?") ? "&" : "?";
        // '=' inside the option value is percent-encoded so the URL parses correctly.
        return base + sep + "options=-ccluster%3D" + vclusterName;
    }
}
