package com.adarosatas.vocabtrim.snapshot;

import com.adarosatas.vocabtrim.common.exception.ApiException;
import com.adarosatas.vocabtrim.snapshot.dto.SnapshotUploadResponse;
import com.adarosatas.vocabtrim.user.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class SnapshotService {
    //##注入多个
    private final SnapshotMapper snapshotMapper;
    private final UserMapper userMapper;
    private final SnapshotValidator snapshotValidator;
    private final EtagService etagService;
    public SnapshotService(
        SnapshotMapper snapshotMapper,
        UserMapper userMapper,
        SnapshotValidator snapshotValidator,
        EtagService etagService
    ) {
        this.snapshotMapper = snapshotMapper;
        this.userMapper = userMapper;
        this.snapshotValidator = snapshotValidator;
        this.etagService = etagService;
    }

    //##方法：下载
    @Transactional(readOnly = true)
    public Snapshot download(long userId) {
        //拿出这人的东西
        Snapshot current = snapshotMapper.findCurrent(userId);
        //如果这人没东西
        if (current == null) {
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "SNAPSHOT_NOT_FOUND",
                "云端还没有可下载的数据"
            );
        }
        return current;
    }

    //##方法：上传
    @Transactional
    public SnapshotUploadResponse upload(
        long userId,
        byte[] payload,
        String ifMatch,
        String ifNoneMatch,
        boolean force
    ) {

        //###数据合法性、取schema版本号
        int schemaVersion = snapshotValidator.validate(payload);

        //###悲观锁用户、确认有其人、取当前快照
        if (userMapper.lockById(userId) == null) {
            throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "登录状态已失效"
            );
        }
        Snapshot current = snapshotMapper.findCurrent(userId);

        //###乐观冲突处理
        //检查本地ETag，或确认强制覆盖
        if (!force) {
            enforcePrecondition(
                current,
                normalizeHeader(ifMatch),
                normalizeHeader(ifNoneMatch)
            );
        }

        //###版本号加一
        long version = current == null ? 1L : current.getVersionNo() + 1L;

        //###填充、存入数据库、删除旧版本、返回响应
        Snapshot snapshot = new Snapshot();
        snapshot.setUserId(userId);
        snapshot.setVersionNo(version);
        snapshot.setEtag(etagService.create(payload, version));
        snapshot.setSchemaVersion(schemaVersion);
        snapshot.setPayload(payload);
        snapshot.setSizeBytes((long) payload.length);

        snapshotMapper.insert(snapshot);
        snapshotMapper.deleteOlderThan(userId, Math.max(1L, version - 1L));

        return new SnapshotUploadResponse(
            snapshot.getEtag(),
            version,
            payload.length,
            Instant.now() //Java当前时刻，不保证和数据库的相同
        );
    }

    //##展开方法：检查本地ETag
    private void enforcePrecondition(
        Snapshot current,
        String ifMatch,
        String ifNoneMatch
    ) {
        //如果什么都没传上来
        if (ifMatch == null && ifNoneMatch == null) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "上传必须携带当前云端版本信息"
            );
        }
        //如果本地ETag无
        if ("*".equals(ifNoneMatch)) {
            if (current != null) throw conflict();
            return;
        }
        //如果本地ETag有
        if (ifMatch != null) {
            if (current == null
                || !Objects.equals(current.getEtag(), ifMatch)) {
                throw conflict();
            }
            return;
        }
        //其他情况抛异常
        throw new ApiException(
            HttpStatus.PRECONDITION_REQUIRED,
            "PRECONDITION_REQUIRED",
            "首次上传请使用 If-None-Match: *"
        );
    }

    //##再展开方法：乐观冲突异常类型
    private ApiException conflict() {
        return new ApiException(
            HttpStatus.CONFLICT,
            "SYNC_CONFLICT",
            "云端数据已经被其他设备更新，请先下载，或确认后强制覆盖"
        );
    }

    //##辅助方法：HTTP首部格式
    private String normalizeHeader(String value) {
        //HTTP首部如果：不存在、""、"   "，统一作为Java的null
        if (value == null || value.isBlank()) return null;
        //有则去掉两边空白
        return value.trim();
    }
}
