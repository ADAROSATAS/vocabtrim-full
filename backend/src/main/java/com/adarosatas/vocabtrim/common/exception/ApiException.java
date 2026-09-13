package com.adarosatas.vocabtrim.common.exception;
//后端异常：在异常父类的基础上加两个字段
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    //构造
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        //调用父类的构造方法、把message传给父类
        this.status = status;
        this.code = code;
    }
}
