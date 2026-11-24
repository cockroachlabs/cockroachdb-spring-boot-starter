package com.cockroachlabs.data.multiregion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.cockroachlabs.data.autoconfigure.CockroachDBProperties.Multiregion.Mode;

/**
 * The problems the multi-region check found, and SQL that fixes them. A contradiction (an
 * annotation that names one locality while the table has the other, or annotations that disagree
 * with each other) is a problem that stops startup even in warn mode.
 */
final class MultiregionReport {

    static final String CONTRADICTION = "Contradiction: ";

    /**
     * Who produced the report, by fully qualified name, so a developer can type it into an IDE
     * and find the class behind the message.
     */
    static final String SOURCE = MultiregionCheck.class.getName();

    static final String PROPERTY = "cockroachdb.multiregion.mode";

    static final String OVERVIEW = "https://docs.cockroachlabs.com/docs/stable/multiregion-overview";

    private final List<String> problems = new ArrayList<>();

    private final List<String> statements = new ArrayList<>();

    private int contradictions;

    void problem(String problem) {
        problems.add(problem);
    }

    void contradiction(String problem) {
        problems.add(CONTRADICTION + problem);
        contradictions++;
    }

    void statement(String statement) {
        statements.add(statement);
    }

    List<String> problems() {
        return List.copyOf(problems);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    boolean isEmpty() {
        return problems.isEmpty();
    }

    boolean hasContradictions() {
        return contradictions > 0;
    }

    /**
     * The report as one message. Its first line names the class that produced it and the
     * property, with its current value, that decides whether it logs or stops startup.
     */
    String format(Mode mode) {
        StringBuilder out = new StringBuilder("CockroachDB multiregion check (")
                .append(SOURCE).append(", ").append(PROPERTY).append('=')
                .append(mode.name().toLowerCase(Locale.ROOT)).append(") found ")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem:" : " problems:");
        for (String problem : problems) {
            out.append("\n  - ").append(problem.replace("\n", "\n    "));
        }
        if (contradictions > 0) {
            out.append("\n\nContradictions stop startup in every mode but none: the annotations and the schema")
                    .append(" disagree about where rows live, so one of them is wrong.");
        }
        if (!statements.isEmpty()) {
            out.append("\n\nSuggested SQL (review, then add to a migration; or alter the annotations):");
            for (String statement : statements) {
                out.append("\n  ").append(statement);
            }
        }
        out.append("\n\nSee also: ").append(OVERVIEW);
        return out.toString();
    }
}
