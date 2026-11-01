package com.manangupta.jobforge.handler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobHandlerRegistryTest {

    private static JobHandler handler(String type) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JobResult execute(JobContext ctx) {
                return JobResult.empty();
            }
        };
    }

    @Test
    void routesByType() {
        JobHandlerRegistry r = new JobHandlerRegistry(List.of(handler("a"), handler("b")));
        assertThat(r.supports("a")).isTrue();
        assertThat(r.supports("zzz")).isFalse();
        assertThat(r.find("b")).isPresent();
        assertThat(r.types()).containsExactly("a", "b");
    }

    @Test
    void rejectsDuplicateTypes() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("dup"), handler("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate job handler");
    }

    @Test
    void rejectsBlankTypes() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler(" "))))
                .isInstanceOf(IllegalStateException.class);
    }
}
