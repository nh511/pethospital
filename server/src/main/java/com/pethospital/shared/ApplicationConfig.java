package com.pethospital.shared;
import java.time.Clock;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration
public class ApplicationConfig {
 @Bean Clock clock(){ return Clock.systemUTC(); }
 @Bean PasswordEncoder passwordEncoder(){ return new BCryptPasswordEncoder(12); }
 @Bean Jackson2ObjectMapperBuilderCustomizer longIds(){ return b->{
  b.serializerByType(Long.class,ToStringSerializer.instance);
  b.serializerByType(Long.TYPE,ToStringSerializer.instance);
 }; }
}

