package com.pethospital.shared;
import jakarta.validation.constraints.*;
public record PageQuery(@Min(1) int page,@Min(1) @Max(100) int pageSize) {
 public PageQuery() { this(1,20); }
 public long offset() { return Math.multiplyExact((long)page-1,pageSize); }
}

