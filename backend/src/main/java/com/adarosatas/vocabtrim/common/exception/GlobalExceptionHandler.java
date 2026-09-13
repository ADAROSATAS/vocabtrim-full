package com.adarosatas.vocabtrim.common.exception;
//后端异常->HTTP响应
import com.adarosatas.vocabtrim.common.api.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //##常量：获取一个专属的日志记录器
    private static final Logger log
        = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    //##主动业务异常
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity
            .status(exception.getStatus())
            .body(ApiError.of(
                exception.getCode(),
                exception.getMessage()
            ));
    }

    //##校验@Valid失败
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception   //###准备错误消息
            .getBindingResult()                   //拿到校验结果
            .getFieldErrors()                     //从中取出所有字段错误
            .stream()                             //变成数据流可逐个处理
            .map(this::formatFieldError)          //逐个错误交给formatFieldError()变成人话
            .collect(Collectors.joining("；"));   //把几段人话加分号连起来
        if (message.isBlank()) { message = "请求参数不正确"; }
        return ResponseEntity
            .badRequest()    //等价.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of(
                "VALIDATION_ERROR",
                message
            ));
    }

    //##也是校验@Valid失败，另一类
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity
            .badRequest()
            .body(ApiError.of(
                "VALIDATION_ERROR",
                exception.getMessage()
            ));
    }

    //##请求内容读不了
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity
            .badRequest()
            .body(ApiError.of(
                "INVALID_REQUEST",
                "请求内容无法读取"
            ));
    }

    //##兜底异常处理
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        //要记日志
        log.error("Unhandled server error", exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of(
                "INTERNAL_ERROR",
                "服务器暂时无法处理请求"
            ));
    }

    //##展开方法：校验错误->人话
    private String formatFieldError(FieldError error) {
        //有现成错误提示就用，没有就自己生成“字段名 不正确”
        return error.getDefaultMessage() ==
            null ? error.getField() + " 不正确"
            : error.getDefaultMessage();
    }
}
