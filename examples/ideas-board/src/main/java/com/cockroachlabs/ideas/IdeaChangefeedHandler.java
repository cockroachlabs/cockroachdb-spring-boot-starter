package com.cockroachlabs.ideas;

import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.annotation.CockroachDBCDC;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Reacts to CockroachDB changefeed events on the {@code ideas} table. The starter exposes
 * a webhook sink; each change row is delivered here as a typed {@link CDCEvent}. This demo
 * handler just counts processed events and remembers the text of the latest inserted idea,
 * standing in for a real side effect (cache update, notification, projection, ...).
 */
@Component
public class IdeaChangefeedHandler {

    private final AtomicInteger processed = new AtomicInteger();
    private volatile String lastNewIdeaText;

    // tag::cdc-handler[]
    @CockroachDBCDC(endpoint = "/ideas", table = "ideas")
    public void onIdeaChange(CDCEvent<Idea> event) {
        processed.incrementAndGet();
        if (event.isInsert() && event.after() != null) {
            lastNewIdeaText = event.after().getText();
        }
    }
    // end::cdc-handler[]

    public int processedCount() {
        return processed.get();
    }

    public String lastNewIdeaText() {
        return lastNewIdeaText;
    }
}
