package com.adarosatas.vocabtrim.snapshot;
//先准备一份合法数据，通过少量修改错误，验证检查功能
import com.adarosatas.vocabtrim.common.exception.ApiException;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotValidatorTest {
    //##准备的检查器
    private final SnapshotValidator validator =
        new SnapshotValidator(JsonMapper.builder().build());

    //##准备的合法快照
    private String validSnapshot() {
        return """
                {
                  "format":"vocabtrim-snapshot",
                  "schemaVersion":1,
                  "lists":[{
                    "id":"list-1",
                    "name":"demo.json",
                    "records":[{"headWord":"hello"}],
                    "statuses":[""],
                    "cursor":0,
                    "shape":{"kind":"array"},
                    "updatedAt":"2026-08-16T00:00:00.000Z"
                  }],
                  "settings":{
                    "defaultDetails":false,
                    "keepDuration":60,
                    "crossDuration":205
                  },
                  "savedAt":1786838400000
                }
                """;
    }

    //##不修改
    @Test
    void acceptsVersionOneSnapshot() {
        //转字节
        byte[] payload
            = validSnapshot().getBytes(StandardCharsets.UTF_8);
        assertThat(validator.validate(payload))
            .isEqualTo(1);
    }

    //##词表records和statuses数量不一致
    @Test
    void rejectsMismatchedRecordAndStatusCounts() {
        //statuses减掉一个
        String invalid = validSnapshot().replace(
            "\"statuses\":[\"\"]",
            "\"statuses\":[]"
        );
        //期望抛出异常
        assertThatThrownBy(
            () -> validator.validate(invalid.getBytes(StandardCharsets.UTF_8))
        )
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("数量不一致");
    }

    //##词表statuses里有未定义标记
    @Test
    void rejectsUnsupportedStatus() {
        //statuses改掉一个
        String invalid = validSnapshot().replace(
            "\"statuses\":[\"\"]",
            "\"statuses\":[\"maybe\"]"
        );
        //期望抛出异常
        assertThatThrownBy(
            () -> validator.validate(invalid.getBytes(StandardCharsets.UTF_8))
        )
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("标记");
    }

}
