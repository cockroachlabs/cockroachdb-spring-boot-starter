package com.cockroachlabs.ideas;

import com.cockroachlabs.data.annotation.CrdbRegion;
import com.cockroachlabs.data.annotation.RegionalByRow;
import com.cockroachlabs.data.multiregion.RegionalByRowListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A REGIONAL BY ROW idea. The {@code @CrdbRegion} field maps to the table's
 * {@code crdb_region} column and is stamped before persist by
 * {@link RegionalByRowListener} from the active {@code RegionContext} (or the
 * {@code defaultRegion} below), so each row lands in the right region's partition.
 */
// tag::regional-entity[]
@Entity
@Table(name = "regional_ideas")
@RegionalByRow(defaultRegion = "us-east1")
@EntityListeners(RegionalByRowListener.class)
public class RegionalIdea {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private int votes;

    @CrdbRegion
    @Column(name = "crdb_region")
    private String region;

    protected RegionalIdea() {
    }

    public RegionalIdea(UUID id, String text, int votes) {
        this.id = id;
        this.text = text;
        this.votes = votes;
    }

    public UUID getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public int getVotes() {
        return votes;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
// end::regional-entity[]
