package com.pethospital.shared;
import java.util.*;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice
public class ApiExceptionHandler {
 private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
 @ExceptionHandler(BusinessException.class)
 ResponseEntity<?> business(BusinessException ex) { return ResponseEntity.status(ex.status).body(ApiResponse.error(ex.code,ex.getMessage(),ex.data)); }
 @ExceptionHandler(MethodArgumentNotValidException.class)
 ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
  Map<String,String> errors=new TreeMap<>();
  ex.getBindingResult().getFieldErrors().forEach(e->errors.putIfAbsent(e.getField(),e.getDefaultMessage()));
  return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR","参数校验失败",Map.of("fieldErrors",errors)));
 }
 @ExceptionHandler({HttpMessageNotReadableException.class,ConstraintViolationException.class,HandlerMethodValidationException.class,MissingServletRequestPartException.class,MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class})
 ResponseEntity<?> malformed(Exception ex) { return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR","请求格式不正确",null)); }
 @ExceptionHandler(MaxUploadSizeExceededException.class)
 ResponseEntity<?> tooLarge(Exception ex) { return ResponseEntity.unprocessableEntity().body(ApiResponse.error("FILE_TOO_LARGE","文件超过10MB限制",null)); }
 @ExceptionHandler(NoResourceFoundException.class)
 ResponseEntity<?> missing(Exception ex) { return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND","资源不存在",null)); }
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
 ResponseEntity<?> methodNotAllowed(Exception ex) { return ResponseEntity.status(405).body(ApiResponse.error("METHOD_NOT_ALLOWED","请求方法不受支持",null)); }
 @ExceptionHandler(Exception.class)
 ResponseEntity<?> unexpected(Exception ex) {
  log.error("Unhandled request failure: type={} traceId={}",ex.getClass().getSimpleName(),TraceFilter.current());
  return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR","服务暂时不可用",null));
 }
}

