package com.chendev.ticketflow.order.factory;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Stateless unit test, no Spring context needed.
class OrderNoFactoryTest {

    // ^ and $ guard against silent drift if someone appends a suffix without updating this test
    private static final Pattern ORDER_NO_FORMAT =
            Pattern.compile("^TF-[0-9a-f]{32}$");

    private final OrderNoFactory factory = new OrderNoFactory();

    @Test
    void format_matches_TF_prefix_plus_32_hex_chars() {
        String orderNo = factory.create();

        assertThat(orderNo)
                .as("orderNo must follow TF-{uuidv7-no-dashes} contract")
                .matches(ORDER_NO_FORMAT)
                .hasSize(35);  // "TF-" (3) + 32 hex = 35; well under VARCHAR(64)
    }

    @Test
    void ten_thousand_creations_produce_zero_collisions() {
        // Birthday-paradox sanity check: 74 random bits means 10K rapid-fire creations should never collide.
        // The previous ms+UUID6 scheme (24 bits of entropy) would fail this deterministically.
        int iterations = 10_000;
        Set<String> created = new HashSet<>(iterations);

        for (int i = 0; i < iterations; i++) {
            created.add(factory.create());
        }

        assertThat(created)
                .as("all %d created orderNos must be unique", iterations)
                .hasSize(iterations);
    }

    @Test
    void successive_creations_are_lexicographically_ordered() {
        // UUIDv7's ms prefix makes string-sort == time-sort; useful for B-tree locality and log scanning.
        // >= not > because two creations in the same ms still order correctly via the random delta.
        String previous = factory.create();

        for (int i = 0; i < 100; i++) {
            String current = factory.create();
            assertThat(current)
                    .as("orderNo[%d] must be >= previous when sorted lexicographically", i)
                    .isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }
}