package com.adarosatas.vocabtrim.snapshot;
//用“快照内容+版本号”计算SHA-256得到ETag

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class EtagService {
    public String create(byte[] payload, long version) {
        try {
            //###哈希算法函数
            MessageDigest digest =
                MessageDigest.getInstance("SHA-256");
            //###加入payload
            digest.update(payload);
            //###加入version版本号
            digest.update(
                ByteBuffer.allocate(Long.BYTES) //整型转字节
                    .putLong(version)           //放入version
                    .array()                    //拿到byte[]
            );

            //###消化、转为十六进制字符串、加引号
            return "\""
                + HexFormat.of().formatHex(digest.digest())
                + "\"";

            //###异常：环境缺乏SHA-256算法
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 is required by the Java platform",
                exception
            );
        }
    }
}
