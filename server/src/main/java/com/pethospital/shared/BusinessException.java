package com.pethospital.shared;
import org.springframework.http.HttpStatus;
public class BusinessException extends RuntimeException {
 public final String code;
 public final HttpStatus status;
 public final Object data;
 public BusinessException(HttpStatus status,String code,String message) { this(status,code,message,null); }
 public BusinessException(HttpStatus status,String code,String message,Object data) {
  super(message);
  this.status=status;
  this.code=code;
  this.data=data;
 }
}

