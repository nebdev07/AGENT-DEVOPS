package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorMessageTest {

    @Test
    void formatsCauseWhereAction() {
        assertThat(OperatorMessage.of("x.", "y", "z"))
                .isEqualTo("Cause : x.\nOù : y\nÀ faire : z");
    }
}
