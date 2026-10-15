package cn.szu.urbantravelplanningagent.controller;

import cn.szu.urbantravelplanningagent.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Result.error(400, error.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> missingParameter(MissingServletRequestParameterException error) {
        return ResponseEntity.badRequest().body(Result.error(400, "缺少参数: " + error.getParameterName()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> statusError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Result.error(error.getStatusCode().value(), error.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> serverError(Exception error) {
        log.error("会话接口处理失败", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("服务暂时不可用，请稍后重试。"));
    }
}
