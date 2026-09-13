package com.adarosatas.vocabtrim.snapshot;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SnapshotMapper {

    //##查这个人最新的快照
    @Select("""
            SELECT id, user_id, version_no, etag, schema_version, payload, size_bytes, created_at
            FROM snapshots
            WHERE user_id = #{userId}
            ORDER BY version_no DESC  -- 新旧以version_no为准而非created_at
            LIMIT 1
            """)
    Snapshot findCurrent(@Param("userId") long userId);
    //如果一条都没有，返回null

    //##保存一个快照
    @Insert("""
            INSERT INTO snapshots (user_id, version_no, etag, schema_version, payload, size_bytes)
            VALUES (#{userId}, #{versionNo}, #{etag}, #{schemaVersion}, #{payload}, #{sizeBytes})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    // 建表.sql文件中自动填写时间戳，这里不用写。
    // 但id（非user_id）要提，因为java对象要拿回来。
    int insert(Snapshot snapshot);

    //##删除老快照
    //实际最终只保留两个，最新版和上一版
    @Delete("""
            DELETE FROM snapshots
            WHERE user_id = #{userId}
              AND version_no < #{minimumVersionToKeep}
            """)
    int deleteOlderThan(
        @Param("userId") long userId,
        @Param("minimumVersionToKeep") long minimumVersionToKeep
    );
}
