package com.adarosatas.vocabtrim.snapshot;

import com.adarosatas.vocabtrim.common.exception.ApiException;
import com.adarosatas.vocabtrim.security.VocabTrimPrincipal;
import com.adarosatas.vocabtrim.snapshot.dto.SnapshotUploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/snapshot")
//下载/上传接口分别是GET/PUT该路径，不用再加子路径
public class SnapshotController {
    //##注入服务
    private final SnapshotService snapshotService;
    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    //##下载
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> download(
        //参数注解：SS根据当前Session恢复认证，注入用户主体
        @AuthenticationPrincipal VocabTrimPrincipal principal
    ) {
        //调用服务得到该用户快照
        Snapshot snapshot = snapshotService.download(principal.getId());
        return ResponseEntity.ok()             //返回builder开始
                .contentType(MediaType.APPLICATION_JSON)  //告诉客户这是JSON
                .cacheControl(CacheControl.noStore())     //浏览器等中间环节不缓存这份响应
                .eTag(stripQuotes(snapshot.getEtag()))    //ETag
                .header(                                  //自定义首部表示应用版本
                    "X-VocabTrim-Version",
                    Long.toString(snapshot.getVersionNo())
                )
                .body(snapshot.getPayload());  //生成最终对象
    }

    //##上传
    @PutMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SnapshotUploadResponse> upload(
            @AuthenticationPrincipal VocabTrimPrincipal principal,
            HttpServletRequest request,  //当前完整HTTP请求
            @RequestParam(
                name = "force",          //对应URL查询参数：PUT /.../snapshot?force=true
                defaultValue = "false"   //默认false，只有当用户确认后才覆盖
            ) boolean force
    ) throws IOException {

        //###检查请求自称的大小，尽早拒绝
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > SnapshotValidator.MAX_BYTES) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "SNAPSHOT_TOO_LARGE",
                "同步数据不能超过 64 MiB");
        }

        //###读取请求实际的大小
        byte[] payload = request
            .getInputStream()
            .readNBytes(
                (int) SnapshotValidator.MAX_BYTES + 1
            );  //最多读取最大值+1，如果读到+1证明超大
        if (payload.length > SnapshotValidator.MAX_BYTES) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "SNAPSHOT_TOO_LARGE",
                "同步数据不能超过 64 MiB"
            );
        }

        //###把信息传给服务处理，并取得上传响应
        SnapshotUploadResponse result =
            snapshotService.upload(
                principal.getId(),
                payload,
                request.getHeader(HttpHeaders.IF_MATCH),       //已有云端版本传
                request.getHeader(HttpHeaders.IF_NONE_MATCH),  //否则传
                force
            );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(stripQuotes(result.etag()))
                .body(result);
    }

    //##辅助方法：处理 Etag 的引号
    private String stripQuotes(String etag) {
        if (etag != null
            && etag.length() >= 2
            && etag.startsWith("\"")
            && etag.endsWith("\"")) {
            return etag.substring(1, etag.length() - 1);
        }
        return etag;
    }
}
