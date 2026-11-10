package com.pethospital.shared;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/hospital")
public class HospitalController {
 private final Map<String,String> info;
 public HospitalController(@Value("${hospital.name}") String name,@Value("${hospital.address}") String address,@Value("${hospital.phone}") String phone,@Value("${hospital.business-hours}") String hours) {
  info=Map.of("name",name,"address",address,"phone",phone,"businessHours",hours);
 }
 @GetMapping public ApiResponse<?> get(){ return ApiResponse.ok(info); }
}

