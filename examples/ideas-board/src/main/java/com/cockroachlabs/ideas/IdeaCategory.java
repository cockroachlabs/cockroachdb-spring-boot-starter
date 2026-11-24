package com.cockroachlabs.ideas;

import com.cockroachlabs.data.annotation.GlobalTable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A GLOBAL reference table. Categories are read on every request from every region but
 * almost never written, which is exactly the shape {@code SET LOCALITY GLOBAL} is for:
 * fast local reads everywhere, writes paid for centrally.
 *
 * <p>{@code @GlobalTable} is the only thing you add — the starter's startup multi-region
 * check (0.6.0+, see {@code cockroachdb.multiregion.mode} in {@code application.yml})
 * compares this annotation against the live table and tells you if they disagree.
 */
// tag::mr-check-entity[]
@Entity
@Table(name = "idea_categories")
@GlobalTable
public class IdeaCategory {

    @Id
    private String slug;

    @Column(nullable = false)
    private String label;

    protected IdeaCategory() {
    }

    public IdeaCategory(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String getSlug() {
        return slug;
    }

    public String getLabel() {
        return label;
    }
}
// end::mr-check-entity[]

// tag::mr-check-report[]
// What the check reports when annotation and schema disagree.
//
// Create idea_categories as an ordinary (non-GLOBAL) table on a multi-region database and
// boot the app. With cockroachdb.multiregion.mode=warn (the default) the starter logs, and
// continues startup:
//
//   CockroachDB multiregion check (...MultiregionCheck, cockroachdb.multiregion.mode=warn)
//   found 1 problem:
//     - Table "public"."idea_categories" (@GlobalTable IdeaCategory) is
//       REGIONAL BY TABLE IN PRIMARY REGION, not GLOBAL.
//
//   Suggested SQL (review, then add to a migration; or alter the annotations):
//     ALTER TABLE "public"."idea_categories" SET LOCALITY GLOBAL;
//
//   See also: https://docs.cockroachlabs.com/docs/stable/multiregion-overview
//
// Fix it either way: run the suggested SQL to make the table GLOBAL, or drop @GlobalTable
// if the table was never meant to be global. Set mode=validate to turn that same message
// into a startup failure in CI; set mode=none to switch the check off.
// end::mr-check-report[]
