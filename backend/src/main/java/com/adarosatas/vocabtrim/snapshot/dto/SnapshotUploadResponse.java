package com.adarosatas.vocabtrim.snapshot.dto;

import java.time.Instant;

public record SnapshotUploadResponse(
    String etag,
    long version,
    long sizeBytes,
    Instant savedAt
) {
}
