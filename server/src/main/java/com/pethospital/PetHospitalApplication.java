package com.pethospital;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
public class PetHospitalApplication {
 public static void main(String[] args) { SpringApplication.run(PetHospitalApplication.class, args); }
}

