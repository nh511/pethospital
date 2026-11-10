package com.pethospital;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.shared.*;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={
 "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
 "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class FoundationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired Clock clock;
 @Test void hospitalHasEnvelopeAndTrace() throws Exception {
  mvc.perform(get("/api/v1/hospital").header("X-Trace-Id","untrusted"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
   .andExpect(jsonPath("$.data.name").isNotEmpty())
   .andExpect(header().exists("X-Trace-Id"))
   .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.not("untrusted")));
 }
 @Test void healthIsPublic() throws Exception {mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));}
 @Test void invalidHttpInputIs400() throws Exception {mvc.perform(post("/api/v1/auth/user/register").contentType("application/json").content("{}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));}
 @Test void publicWhitelistDoesNotIncludeHospitalSubpaths() throws Exception {mvc.perform(get("/api/v1/hospital/not-found")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));}
 @Test void otherBusinessEndpointsFailClosed() throws Exception {mvc.perform(get("/api/v1/pets")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.traceId").isNotEmpty());}
 @Test void idsPreservePrecision() throws Exception {assertThat(mapper.writeValueAsString(Map.of("id",9007199254740993L))).contains("\"9007199254740993\"");}
 @Test void paginationHasBoundaries() {
  try(var factory=Validation.buildDefaultValidatorFactory()){
   var v=factory.getValidator();
   assertThat(v.validate(new PageQuery(0,101))).hasSize(2);
   assertThat(v.validate(new PageQuery())).isEmpty();
   assertThat(new PageQuery(2,20).offset()).isEqualTo(20);
  }
 }
 @Test void utcClockAndTraceCleanup(){assertThat(clock.getZone().getId()).isEqualTo("Z");assertThat(TraceFilter.current()).isNull();}
}

