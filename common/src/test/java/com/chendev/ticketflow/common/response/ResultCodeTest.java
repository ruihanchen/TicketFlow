package com.chendev.ticketflow.common.response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

//keep common Spring-free; use int instead of HttpStatus
//validates the whitelist without the Spring overhead. Runs in ms.
class ResultCodeTest {

    private static final Set<Integer> ALLOWED_HTTP_STATUSES =
            Set.of(200, 400, 401, 403, 404, 409, 500, 503);

    @ParameterizedTest
    @EnumSource(ResultCode.class)
    void http_status_in_whitelist(ResultCode rc) {
        assertThat(ALLOWED_HTTP_STATUSES)
                .as("ResultCode.%s httpStatus=%d not in whitelist", rc.name(), rc.getHttpStatus())
                .contains(rc.getHttpStatus());
    }

    @ParameterizedTest
    @EnumSource(ResultCode.class)
    void message_not_blank(ResultCode rc) {
        assertThat(rc.getMessage())
                .as("ResultCode.%s has blank message", rc.name())
                .isNotBlank();
    }

    @Test
    void success_is_zero() {
        assertThat(ResultCode.SUCCESS.getCode()).isEqualTo(0);
        assertThat(ResultCode.SUCCESS.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void business_codes_unique() {
        int[] codes = Arrays.stream(ResultCode.values())
                .mapToInt(ResultCode::getCode)
                .toArray();
        assertThat(codes).doesNotHaveDuplicates();
    }
}
