package com.adarosatas.vocabtrim.snapshot;
//检查数据合法
import com.adarosatas.vocabtrim.common.exception.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SnapshotValidator {
    //##常量
    public static final long MAX_BYTES = 64L * 1024L * 1024L;
    private static final String FORMAT = "vocabtrim-snapshot";
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> ALLOWED_STATUSES = Set.of("", "keep", "crossed");

    //##注入JSON解析工具
    private final JsonMapper objectMapper;
    public SnapshotValidator(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    //##总入口
    public int validate(byte[] payload) {
        //0<快照大小<=MAX_BYTES
        if (payload == null || payload.length == 0) {
            throw badSnapshot("同步数据不能为空");
        }
        if (payload.length > MAX_BYTES) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "SNAPSHOT_TOO_LARGE",
                "同步数据不能超过 64 MiB"
            );
        }

        //把原始字节解析成 JSON 树
        //基本的JSON要求
        final JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw badSnapshot("同步数据不是有效的 JSON");
        }
        if (root == null || !root.isObject()) {
            throw badSnapshot("同步数据必须是 JSON 对象");
        }

        //个性化的JSON要求
        /*
        {
          "format": "vocabtrim-snapshot",  对应常量
          "schemaVersion": 1,              对应常量
          "lists": [...],
          "settings": {...},
          "savedAt": 1786838400000
        }
         */
        if (!FORMAT.equals(root.path("format").asText())) {
            throw badSnapshot("不支持的同步数据格式");
        }
        if (!root.path("schemaVersion").canConvertToInt()
            || root.path("schemaVersion").asInt() != SCHEMA_VERSION) {
            throw badSnapshot("不支持的同步数据版本");
        }
        if (!root.path("lists").isArray()) {
            throw badSnapshot("同步数据缺少词表列表");
        }
        if (!root.path("settings").isObject()) {
            throw badSnapshot("同步数据缺少设置");
        }
        if (!root.path("savedAt").isNumber()) {
            throw badSnapshot("同步数据缺少保存时间");
        }

        //检查设置
        validateSettings(root.path("settings"));
        //检查每张词表
        for (JsonNode list : root.path("lists")) {
            validateList(list);
        }

        return SCHEMA_VERSION;
    }

    //##检查设置
    private void validateSettings(JsonNode settings) {
        //require类型名（settings里的，某个字段）
        requireBoolean(settings, "defaultDetails");
        requireDuration(settings, "keepDuration", 0, 2000);
        requireDuration(settings, "crossDuration", 0, 2000);
    }

    //##检查每张词表
    private void validateList(JsonNode list) {
        if (!list.isObject()) {
            throw badSnapshot("词表条目必须是对象");
        }
        requireText(list, "id");
        requireText(list, "name");
        requireText(list, "updatedAt");
        if (!list.path("shape").isObject()) {
            throw badSnapshot("词表 shape 无效");
        }

        //词条与标记要对应
        JsonNode records = list.path("records");
        JsonNode statuses = list.path("statuses");
        if (!records.isArray() || !statuses.isArray()) {
            throw badSnapshot("词表 records/statuses 无效");
        }
        if (records.size() != statuses.size()) {
            throw badSnapshot("词表 records 与 statuses 数量不一致");
        }

        //每个词条合法
        for (JsonNode record : records) {
            if (!record.isObject()) {
                throw badSnapshot("词条必须是 JSON 对象");
            }
        }

        //每个标记合法
        for (JsonNode status : statuses) {
            if (!status.isTextual()
                || !ALLOWED_STATUSES.contains(status.asText())) {
                throw badSnapshot("存在不支持的词条标记");
            }
        }

        //书签
        JsonNode cursor = list.path("cursor");
        if (!cursor.canConvertToInt()) {
            throw badSnapshot("词表 cursor 无效");
        }
        int maximum = Math.max(0, records.size() - 1);
        int value = cursor.asInt();
        if (value < 0
            || (records.size() > 0 && value > maximum)
            || (records.size() == 0 && value != 0)) {
            throw badSnapshot("词表 cursor 超出范围");
        }
    }

    //##辅助方法：类型取值判断，异常

    private void requireText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
            throw badSnapshot("字段 " + field + " 无效");
        }
    }
    private void requireBoolean(JsonNode node, String field) {
        if (!node.path(field).isBoolean()) {
            throw badSnapshot("设置 " + field + " 无效");
        }
    }
    private void requireDuration(JsonNode node, String field, int min, int max) {
        JsonNode value = node.path(field);
        if (!value.canConvertToInt()
            || value.asInt() < min
            || value.asInt() > max) {
            throw badSnapshot("设置 " + field + " 无效");
        }
    }
    private ApiException badSnapshot(String message) {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_SNAPSHOT",
            message
        );
    }
}
