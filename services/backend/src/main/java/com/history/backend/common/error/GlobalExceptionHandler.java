package com.history.backend.common.error;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 도메인 예외를 공통 API 에러 응답으로 변환한다.
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NotFoundException exception) {
        return errorResponse(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException exception) {
        return errorResponse(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        List<FieldErrorDetail> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Request validation failed.",
                        fields
                ));
    }

    private ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(
                        status.value(),
                        status.getReasonPhrase(),
                        message
                ));
    }
}
