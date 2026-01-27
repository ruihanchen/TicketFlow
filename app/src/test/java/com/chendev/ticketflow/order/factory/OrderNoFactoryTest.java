package com.chendev.ticketflow.order.factory;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Stateless unit test(no Spring context, runs in milliseconds).
class OrderNoFactoryTest {

    // ^ and $ guard against silent drift if someone appends a suffix without updating this test
    private static final Pattern ORDER_NO_FORMAT  = Pattern.compile("^TF-[0-9a-f]{32}$");
    private static final int     UNIQUENESS_ITERS = 10_000;
    private static final int     ORDERING_ITERS   = 100;

    private final OrderNoFactory factory = new OrderNoFactory();

    @Test
    void format_matches_TF_prefix_plus_32_lowercase_hex_chars() {
        String orderNo = factory.create();

        assertThat(orderNo)
                .as("orderNo must follow TF-{uuidv7-no-dashes} contract")
                .matches(ORDER_NO_FORMAT);

        // "TF-" (3) + 32 hex = 35; well under VARCHAR(64)
        assertThat(orderNo).hasSize(35);
    }

    @Test
    void ten_thousand_creations_produce_zero_collisions() {
        // With 74 random bits, birthday-paradox collision at 10K IDs is ~2.6e-15.
        // The previous ms+UUID6 scheme (24 bits) would fail this deterministically.
        Set<String> created = new HashSet<>(UNIQUENESS_ITERS);
        for (int i = 0; i < UNIQUENESS_ITERS; i++) {
            created.add(factory.create());
        }
        assertThat(created).hasSize(UNIQUENESS_ITERS);
    }

    @Test
    void successive_creations_are_lexicographically_non_decreasing() {
        // UUIDv7's ms prefix makes string-sort equals to time-sort(B-tree locality + readable log order).
        // >= not >, because two calls in the same ms share the same prefix; strict ordering isn't guaranteed.
        String previous = factory.create();
        for (int i = 0; i < ORDERING_ITERS; i++) {
            String current = factory.create();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }
}