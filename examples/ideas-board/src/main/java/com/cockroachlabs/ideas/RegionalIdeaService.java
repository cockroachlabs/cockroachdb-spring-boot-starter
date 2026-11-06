package com.cockroachlabs.ideas;

import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.template.CockroachDBTemplate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Region-scoped writes for {@link RegionalIdea}. Each create pins the work to a region
 * so the row is written to that region's partition.
 */
@Service
public class RegionalIdeaService {

    private final RegionalIdeaRepository repository;
    private final CockroachDBTemplate crdb;

    public RegionalIdeaService(RegionalIdeaRepository repository, CockroachDBTemplate crdb) {
        this.repository = repository;
        this.crdb = crdb;
    }

    // tag::inregion-usage[]
    /**
     * Pin this unit of work to {@code region}: {@link CockroachDBTemplate#inRegion}
     * issues {@code SET crdb_region} for the transaction, and the same
     * {@link RegionContext} stamps the entity's {@code @CrdbRegion} field, so the row
     * is written into that region.
     */
    public RegionalIdea createInRegion(String region, String text) {
        try (RegionContext.RegionScope scope = RegionContext.scoped(region)) {
            return crdb.inRegion(region).execute(
                    () -> repository.save(new RegionalIdea(UUID.randomUUID(), text, 0)));
        }
    }

    /** Region fixed at the call site via the annotation instead of RegionContext. */
    @CockroachDB(region = "us-east1")
    public RegionalIdea createInUsEast(String text) {
        try (RegionContext.RegionScope scope = RegionContext.scoped("us-east1")) {
            return repository.save(new RegionalIdea(UUID.randomUUID(), text, 0));
        }
    }
    // end::inregion-usage[]

    public RegionalIdea find(UUID id) {
        return repository.findById(id).orElseThrow();
    }
}
