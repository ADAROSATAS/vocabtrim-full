package com.adarosatas.vocabtrim.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EtagServiceTest {
    //同package不用import，非注入
    private final EtagService service = new EtagService();

    //##即使载荷不变，只要version变，ETag就变
    @Test
    void etagChangesWhenVersionChangesEvenForSamePayload() {
        byte[] payload = "{}".getBytes();
        assertThat(service.create(payload, 1))
            .isNotEqualTo(service.create(payload, 2));
    }

    //##Etag格式正常
    @Test
    void etagIsQuotedSha256Token() {
        assertThat(service.create("demo".getBytes(), 1))
            .matches("\\\"[0-9a-f]{64}\\\"");
    }
}
