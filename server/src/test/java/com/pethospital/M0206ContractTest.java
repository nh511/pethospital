package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "springdoc.api-docs.enabled=true",
    "identity.jwt.allow-ephemeral=true"
})
@AutoConfigureMockMvc
class M0206ContractTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Set<String> IMPLEMENTED_OPERATIONS = Set.of(
        "GET /hospital",
        "POST /auth/user/register",
        "POST /auth/user/login",
        "POST /auth/wechat/login",
        "POST /auth/web/login",
        "GET /me",
        "PATCH /me",
        "GET /me/menus",
        "GET /policies/current",
        "GET /me/consents",
        "POST /me/consents",
        "GET /admin/users",
        "POST /admin/users",
        "GET /admin/users/{userId}",
        "PATCH /admin/users/{userId}",
        "POST /admin/users/{userId}/disable",
        "POST /admin/users/{userId}/enable",
        "POST /admin/users/{userId}/reset-password",
        "GET /admin/users/{userId}/roles",
        "PUT /admin/users/{userId}/roles",
        "GET /admin/roles",
        "POST /admin/roles",
        "PATCH /admin/roles/{roleId}",
        "GET /admin/roles/{roleId}/menus",
        "PUT /admin/roles/{roleId}/menus",
        "GET /admin/menus",
        "POST /admin/menus",
        "PATCH /admin/menus/{menuId}",
        "POST /files",
        "GET /files/{fileId}",
        "POST /files/{fileId}/download-ticket",
        "POST /files/{fileId}/discard",
        "GET /notices",
        "POST /notices/{noticeId}/read",
        "GET /admin/audit-logs",
        "GET /admin/ai/call-logs",
        "GET /pets",
        "POST /pets",
        "GET /pets/{petId}",
        "PATCH /pets/{petId}",
        "POST /pets/{petId}/archive",
        "GET /pets/{petId}/health-logs",
        "POST /pets/{petId}/health-logs",
        "GET /pets/{petId}/health-logs/{logId}",
        "PATCH /pets/{petId}/health-logs/{logId}",
        "GET /pets/{petId}/timeline",
        "GET /admin/pets",
        "GET /admin/pets/{petId}",
        "PATCH /admin/pets/{petId}",
        "GET /admin/pets/{petId}/health-logs",
        "GET /admin/doctors",
        "POST /admin/doctors",
        "PATCH /admin/doctors/{doctorId}",
        "POST /admin/doctors/{doctorId}/disable",
        "POST /admin/doctors/{doctorId}/enable",
        "GET /staff/doctor-profile",
        "GET /staff/dashboard",
        "GET /staff/pets",
        "GET /staff/pets/{petId}",
        "GET /staff/pets/{petId}/health-logs",
        "POST /appointments",
        "GET /appointments",
        "GET /appointments/{appointmentId}",
        "GET /staff/appointments",
        "GET /staff/appointments/{appointmentId}",
        "GET /staff/appointments/{appointmentId}/status-logs",
        "POST /staff/appointments/{appointmentId}/confirm",
        "POST /staff/appointments/{appointmentId}/reject",
        "POST /appointments/{appointmentId}/cancel",
        "POST /staff/appointments/{appointmentId}/cancel",
        "POST /staff/appointments/{appointmentId}/start",
        "POST /staff/appointments/{appointmentId}/no-show",
        "GET /appointments/{appointmentId}/change-requests",
        "POST /appointments/{appointmentId}/change-requests",
        "GET /staff/appointments/{appointmentId}/change-requests",
        "POST /staff/appointments/{appointmentId}/change-requests",
        "POST /change-requests/{changeRequestId}/accept",
        "POST /change-requests/{changeRequestId}/reject",
        "POST /change-requests/{changeRequestId}/withdraw",
        "GET /staff/appointments/{appointmentId}/medical-draft",
        "PUT /staff/appointments/{appointmentId}/medical-draft",
        "POST /staff/appointments/{appointmentId}/complete",
        "POST /staff/medical-records/{recordId}/revisions",
        "GET /pets/{petId}/medical-records",
        "GET /medical-records/{recordId}",
        "GET /medical-records/{recordId}/versions",
        "GET /staff/pets/{petId}/medical-records",
        "PATCH /staff/doctor-profile",
        "GET /doctors",
        "GET /doctors/{doctorId}",
        "GET /doctors/{doctorId}/slots",
        "GET /staff/slots",
        "POST /staff/slots",
        "PATCH /staff/slots/{slotId}",
        "POST /staff/slots/{slotId}/close",
        "POST /staff/slots/{slotId}/open",
        "GET /staff/health/guides",
        "POST /staff/health/guides",
        "GET /staff/health/guides/{guideId}",
        "PATCH /staff/health/guides/{guideId}",
        "POST /staff/health/guides/{guideId}/publish",
        "POST /staff/health/guides/{guideId}/offline",
        "GET /health/guides",
        "GET /health/guides/{guideId}",
        "GET /staff/health/standards",
        "POST /staff/health/standards",
        "GET /staff/health/standards/{standardId}",
        "PATCH /staff/health/standards/{standardId}",
        "POST /staff/health/standards/{standardId}/publish",
        "POST /staff/health/standards/{standardId}/offline",
        "GET /health/standards",
        "GET /staff/triage-rules",
        "GET /staff/knowledge/documents",
        "POST /staff/knowledge/documents",
        "GET /staff/knowledge/documents/{documentId}",
        "PATCH /staff/knowledge/documents/{documentId}",
        "GET /staff/knowledge/documents/{documentId}/versions",
        "POST /staff/knowledge/documents/{documentId}/versions",
        "GET /staff/knowledge/versions/{knowledgeVersionId}",
        "PATCH /staff/knowledge/versions/{knowledgeVersionId}",
        "POST /staff/knowledge/versions/{knowledgeVersionId}/submit-review",
        "POST /staff/knowledge/versions/{knowledgeVersionId}/review",
        "POST /staff/triage-rules",
        "GET /staff/triage-rules/{ruleId}",
        "PATCH /staff/triage-rules/{ruleId}",
        "POST /staff/triage-rules/{ruleId}/review",
        "POST /staff/triage-rules/{ruleId}/submit-review",
        "POST /staff/triage-rules/{ruleId}/disable",
        "POST /staff/triage-rules/{ruleId}/enable",
        "POST /staff/triage-rules/test"
    );

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void frozenContractIsOpenApi303WithExactly158UniqueOperations() throws Exception {
        JsonNode frozen = frozenContract();
        assertThat(frozen.path("openapi").asText()).isEqualTo("3.0.3");

        Set<String> operations = operations(frozen.path("paths"), "");
        assertThat(operations).hasSize(158);
        assertThat(operations).contains("GET /hospital");

        long implemented = operations.stream()
            .filter(key -> operation(frozen, key).path("x-implementation-status").asText().equals("IMPLEMENTED"))
            .count();
        assertThat(implemented).isEqualTo(IMPLEMENTED_OPERATIONS.size());
        assertThat(operation(frozen, "GET /hospital").path("x-owner-task").asText()).isEqualTo("M01-06");
        assertThat(operation(frozen, "POST /auth/user/register").path("x-owner-task").asText()).isEqualTo("M03-01");
        assertThat(operation(frozen, "POST /auth/wechat/login").path("x-owner-task").asText()).isEqualTo("M03-02");
        assertThat(operation(frozen, "POST /auth/wechat/login").path("x-request-schema").asText())
            .isEqualTo("WechatLoginInput");
        assertThat(operation(frozen, "GET /me").path("x-owner-task").asText()).isEqualTo("M03-05");
        assertThat(operation(frozen, "GET /me/menus").path("x-audience").asText()).isEqualTo("WEB");
        assertThat(operation(frozen, "POST /admin/users").path("x-owner-task").asText()).isEqualTo("M03-06");
        assertThat(operation(frozen, "PATCH /admin/users/{userId}").path("x-request-schema").asText())
            .isEqualTo("AdminUserPatchInput");
        assertThat(operation(frozen, "PUT /admin/roles/{roleId}/menus").path("x-owner-task").asText())
            .isEqualTo("M03-07");
        assertThat(operation(frozen, "POST /admin/menus").path("x-request-schema").asText())
            .isEqualTo("AdminMenuCreateInput");
        assertThat(operation(frozen, "POST /admin/users/{userId}/disable").path("x-owner-task").asText())
            .isEqualTo("M03-08");
        assertThat(operation(frozen, "POST /admin/users/{userId}/reset-password")
            .path("x-request-schema").asText()).isEqualTo("AdminPasswordResetInput");
        assertThat(operation(frozen, "GET /policies/current").path("x-owner-task").asText())
            .isEqualTo("M03-09");
        assertThat(operation(frozen, "POST /me/consents").path("x-request-schema").asText())
            .isEqualTo("ConsentCommand");
        assertThat(operation(frozen, "POST /files/{fileId}/download-ticket")
            .path("x-request-schema").asText()).isEqualTo("FileDownloadTicketInput");
        assertThat(operation(frozen, "POST /files/{fileId}/discard")
            .path("x-response-schema").asText()).isEqualTo("FileDiscardResponse");
        assertThat(operation(frozen, "GET /notices").path("x-response-schema").asText())
            .isEqualTo("NoticePageResponse");
        assertThat(operation(frozen, "POST /notices/{noticeId}/read").has("requestBody"))
            .isFalse();
        assertThat(operation(frozen, "GET /admin/audit-logs").path("x-response-schema").asText())
            .isEqualTo("AuditLogPageResponse");
        assertThat(operation(frozen, "GET /admin/ai/call-logs").path("x-response-schema").asText())
            .isEqualTo("AiCallLogPageResponse");
        assertThat(operation(frozen, "GET /pets").path("x-response-schema").asText())
            .isEqualTo("PetPageResponse");
        assertThat(operation(frozen, "POST /pets").path("x-request-schema").asText())
            .isEqualTo("PetInput");
        assertThat(operation(frozen, "GET /pets/{petId}").path("x-response-schema").asText())
            .isEqualTo("PetDetailResponse");
        assertThat(operation(frozen, "PATCH /pets/{petId}").path("x-request-schema").asText())
            .isEqualTo("PetUpdateInput");
        assertThat(operation(frozen, "POST /pets/{petId}/archive").path("x-request-schema").asText())
            .isEqualTo("PetArchiveInput");
        assertThat(operation(frozen, "POST /pets/{petId}/archive").path("x-response-schema").asText())
            .isEqualTo("PetArchiveResponse");
        assertThat(operation(frozen, "POST /pets/{petId}/health-logs").path("x-request-schema").asText())
            .isEqualTo("HealthLogInput");
        assertThat(operation(frozen, "GET /pets/{petId}/health-logs").path("x-response-schema").asText())
            .isEqualTo("HealthLogPageResponse");
        assertThat(operation(frozen, "GET /pets/{petId}/health-logs/{logId}").path("x-response-schema").asText())
            .isEqualTo("HealthLogResponse");
        assertThat(operation(frozen, "PATCH /pets/{petId}/health-logs/{logId}").path("x-request-schema").asText())
            .isEqualTo("HealthLogUpdateInput");
        JsonNode timeline = operation(frozen, "GET /pets/{petId}/timeline");
        assertThat(timeline.path("x-response-schema").asText()).isEqualTo("TimelinePageResponse");
        assertThat(timeline.path("parameters")).anySatisfy(parameter ->
            assertThat(parameter.path("$ref").asText()).isEqualTo("#/components/parameters/TimelineCursor"));
        JsonNode timelineItem = frozen.path("components").path("schemas").path("TimelineItem");
        assertThat(timelineItem.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(timelineItem.path("properties").path("id").path("$ref").asText())
            .isEqualTo("#/components/schemas/Id");
        assertThat(operation(frozen, "GET /admin/pets").path("x-response-schema").asText())
            .isEqualTo("AdminPetPageResponse");
        assertThat(operation(frozen, "PATCH /admin/pets/{petId}").path("x-request-schema").asText())
            .isEqualTo("AdminPetPatchInput");
        assertThat(operation(frozen, "GET /admin/pets/{petId}/health-logs").path("x-response-schema").asText())
            .isEqualTo("HealthLogPageResponse");
        assertThat(operation(frozen, "GET /staff/dashboard").path("x-response-schema").asText())
            .isEqualTo("StaffDashboardResponse");
        assertThat(operation(frozen, "GET /staff/pets").path("x-response-schema").asText())
            .isEqualTo("StaffPetPageResponse");
        assertThat(operation(frozen, "GET /staff/pets/{petId}").path("x-response-schema").asText())
            .isEqualTo("StaffPetDetailResponse");
        assertThat(operation(frozen, "GET /staff/pets/{petId}/health-logs").path("x-response-schema").asText())
            .isEqualTo("HealthLogPageResponse");
        JsonNode booking = operation(frozen, "POST /appointments");
        assertThat(booking.path("x-request-schema").asText()).isEqualTo("CreateAppointmentInput");
        assertThat(booking.path("x-response-schema").asText()).isEqualTo("AppointmentResponse");
        assertThat(booking.path("responses").has("201")).isTrue();
        assertThat(operation(frozen, "GET /appointments").path("x-response-schema").asText())
            .isEqualTo("AppointmentReadPageResponse");
        assertThat(operation(frozen, "GET /appointments/{appointmentId}").path("x-response-schema").asText())
            .isEqualTo("AppointmentReadDetailResponse");
        assertThat(operation(frozen, "GET /staff/appointments").path("parameters")).anySatisfy(parameter ->
            assertThat(parameter.path("$ref").asText()).isEqualTo("#/components/parameters/AppointmentDate"));
        assertThat(operation(frozen, "GET /staff/appointments/{appointmentId}/status-logs").path("x-response-schema").asText())
            .isEqualTo("AppointmentStatusResponse");
        assertThat(operation(frozen, "POST /staff/appointments/{appointmentId}/confirm").path("x-request-schema").asText())
            .isEqualTo("AppointmentConfirmInput");
        JsonNode rejection = operation(frozen, "POST /staff/appointments/{appointmentId}/reject");
        assertThat(rejection.path("x-request-schema").asText()).isEqualTo("AppointmentRejectInput");
        assertThat(rejection.path("x-response-schema").asText()).isEqualTo("AppointmentDecisionResponse");
        assertThat(operation(frozen, "POST /appointments/{appointmentId}/cancel").path("x-request-schema").asText())
            .isEqualTo("AppointmentCancelInput");
        assertThat(operation(frozen, "POST /staff/appointments/{appointmentId}/cancel").path("x-response-schema").asText())
            .isEqualTo("AppointmentCancellationResponse");
        assertThat(operation(frozen, "POST /staff/appointments/{appointmentId}/start").path("x-request-schema").asText())
            .isEqualTo("AppointmentStartInput");
        JsonNode noShow = operation(frozen, "POST /staff/appointments/{appointmentId}/no-show");
        assertThat(noShow.path("x-request-schema").asText()).isEqualTo("AppointmentNoShowInput");
        assertThat(noShow.path("x-response-schema").asText()).isEqualTo("AppointmentConsultationActionResponse");
        assertThat(operation(frozen, "POST /appointments/{appointmentId}/change-requests").path("x-request-schema").asText())
            .isEqualTo("AppointmentChangeCreateInput");
        assertThat(operation(frozen, "GET /staff/appointments/{appointmentId}/change-requests").path("x-response-schema").asText())
            .isEqualTo("AppointmentChangeHistoryResponse");
        JsonNode accept = operation(frozen, "POST /change-requests/{changeRequestId}/accept");
        assertThat(accept.path("x-request-schema").asText()).isEqualTo("AppointmentChangeAcceptInput");
        assertThat(accept.path("x-response-schema").asText()).isEqualTo("AppointmentChangeAcceptedResponse");
        assertThat(accept.path("x-audience").asText()).isEqualTo("AUTHENTICATED");
        assertThat(operation(frozen, "POST /change-requests/{changeRequestId}/reject").path("x-request-schema").asText())
            .isEqualTo("AppointmentChangeRejectInput");
        assertThat(operation(frozen, "POST /change-requests/{changeRequestId}/withdraw").path("x-response-schema").asText())
            .isEqualTo("AppointmentChangeClosedResponse");
        assertThat(operation(frozen, "GET /staff/appointments/{appointmentId}/medical-draft").path("x-response-schema").asText())
            .isEqualTo("MedicalDraftResponse");
        assertThat(operation(frozen, "PUT /staff/appointments/{appointmentId}/medical-draft").path("x-request-schema").asText())
            .isEqualTo("MedicalDraftSaveInput");
        JsonNode complete = operation(frozen, "POST /staff/appointments/{appointmentId}/complete");
        assertThat(complete.path("x-request-schema").asText()).isEqualTo("MedicalCompleteInput");
        assertThat(complete.path("x-response-schema").asText()).isEqualTo("MedicalCompleteResponse");
        JsonNode revision = operation(frozen, "POST /staff/medical-records/{recordId}/revisions");
        assertThat(revision.path("x-request-schema").asText()).isEqualTo("MedicalRevisionInput");
        assertThat(revision.path("x-response-schema").asText()).isEqualTo("MedicalRevisionResponse");
        assertThat(revision.path("x-audience").asText()).isEqualTo("WEB");
        JsonNode medicalDetail = operation(frozen, "GET /medical-records/{recordId}");
        assertThat(medicalDetail.path("x-audience").asText()).isEqualTo("AUTHENTICATED");
        assertThat(medicalDetail.path("x-response-schema").asText()).isEqualTo("MedicalRecordDetailResponse");
        assertThat(operation(frozen, "GET /medical-records/{recordId}/versions").path("x-response-schema").asText())
            .isEqualTo("MedicalVersionListResponse");
        assertThat(operation(frozen, "GET /staff/pets/{petId}/medical-records").path("x-response-schema").asText())
            .isEqualTo("MedicalRecordListResponse");
        assertThat(operation(frozen, "POST /admin/doctors").path("x-request-schema").asText())
            .isEqualTo("AdminDoctorCreateInput");
        assertThat(operation(frozen, "PATCH /staff/doctor-profile").path("x-request-schema").asText())
            .isEqualTo("StaffDoctorPatchInput");
        assertThat(operation(frozen, "POST /admin/doctors/{doctorId}/disable")
            .path("x-request-schema").asText()).isEqualTo("AdminDoctorDisableInput");
        assertThat(operation(frozen, "POST /admin/doctors/{doctorId}/enable")
            .path("x-response-schema").asText()).isEqualTo("DoctorProfileResponse");
        assertThat(operation(frozen, "GET /doctors").path("x-response-schema").asText())
            .isEqualTo("PublicDoctorPageResponse");
        assertThat(operation(frozen, "GET /doctors/{doctorId}").path("x-response-schema").asText())
            .isEqualTo("PublicDoctorResponse");
        assertThat(operation(frozen, "GET /doctors/{doctorId}/slots").path("x-response-schema").asText())
            .isEqualTo("PublicSlotListResponse");
        assertThat(operation(frozen, "POST /staff/slots").path("x-request-schema").asText())
            .isEqualTo("SlotCreateInput");
        assertThat(operation(frozen, "GET /staff/slots").path("x-response-schema").asText())
            .isEqualTo("StaffSlotListResponse");
        assertThat(operation(frozen, "PATCH /staff/slots/{slotId}").path("x-request-schema").asText())
            .isEqualTo("SlotCapacityPatchInput");
        assertThat(operation(frozen, "POST /staff/slots/{slotId}/close").path("x-request-schema").asText())
            .isEqualTo("SlotCloseInput");
        assertThat(operation(frozen, "POST /staff/health/guides").path("x-request-schema").asText())
            .isEqualTo("HealthGuideCreateInput");
        assertThat(operation(frozen, "GET /staff/health/guides").path("x-response-schema").asText())
            .isEqualTo("HealthGuidePageResponse");
        assertThat(operation(frozen, "POST /staff/health/guides/{guideId}/publish")
            .path("x-request-schema").asText()).isEqualTo("HealthGuidePublishInput");
        assertThat(operation(frozen, "POST /staff/health/guides/{guideId}/offline")
            .path("x-request-schema").asText()).isEqualTo("HealthGuideOfflineInput");
        assertThat(operation(frozen, "GET /health/guides").path("x-response-schema").asText())
            .isEqualTo("PublicGuidePageResponse");
        assertThat(operation(frozen, "GET /health/guides/{guideId}").path("x-response-schema").asText())
            .isEqualTo("PublicGuideDetailResponse");
        assertThat(operation(frozen, "POST /staff/health/standards").path("x-request-schema").asText())
            .isEqualTo("HealthStandardCreateInput");
        assertThat(operation(frozen, "PATCH /staff/health/standards/{standardId}")
            .path("x-request-schema").asText()).isEqualTo("HealthStandardPatchInput");
        assertThat(operation(frozen, "GET /staff/health/standards").path("x-response-schema").asText())
            .isEqualTo("HealthStandardPageResponse");
        assertThat(operation(frozen, "POST /staff/health/standards/{standardId}/publish")
            .path("x-request-schema").asText()).isEqualTo("HealthStandardPublishInput");
        assertThat(operation(frozen, "POST /staff/health/standards/{standardId}/offline")
            .path("x-request-schema").asText()).isEqualTo("HealthStandardOfflineInput");
        assertThat(operation(frozen, "GET /health/standards").path("x-response-schema").asText())
            .isEqualTo("PublicHealthStandardListResponse");
        assertThat(operation(frozen, "POST /staff/triage-rules").path("x-request-schema").asText())
            .isEqualTo("TriageRuleCreateInput");
        assertThat(operation(frozen, "POST /staff/knowledge/documents/{documentId}/versions").path("x-request-schema").asText())
            .isEqualTo("KnowledgeCreateVersionInput");
        assertThat(operation(frozen, "GET /staff/knowledge/versions/{knowledgeVersionId}").path("x-response-schema").asText())
            .isEqualTo("KnowledgeVersionDetailResponse");
        assertThat(operation(frozen, "POST /staff/knowledge/versions/{knowledgeVersionId}/review").path("x-request-schema").asText())
            .isEqualTo("KnowledgeReviewInput");
        assertThat(operation(frozen, "POST /staff/knowledge/versions/{knowledgeVersionId}/submit-review").path("x-response-schema").asText())
            .isEqualTo("KnowledgeReviewResponse");
        assertThat(operation(frozen, "PATCH /staff/triage-rules/{ruleId}").path("x-request-schema").asText())
            .isEqualTo("TriageRulePatchInput");
        assertThat(operation(frozen, "POST /staff/triage-rules/{ruleId}/submit-review")
            .path("x-request-schema").asText()).isEqualTo("TriageRuleSubmitReviewInput");
        assertThat(operation(frozen, "POST /staff/triage-rules/{ruleId}/review")
            .path("x-request-schema").asText()).isEqualTo("TriageRuleReviewInput");
        assertThat(operation(frozen, "POST /staff/triage-rules/{ruleId}/enable")
            .path("x-request-schema").asText()).isEqualTo("TriageRuleEnableInput");
        assertThat(operation(frozen, "POST /staff/triage-rules/{ruleId}/disable")
            .path("x-request-schema").asText()).isEqualTo("TriageRuleDisableInput");
        assertThat(operation(frozen, "POST /staff/triage-rules/test")
            .path("x-response-schema").asText()).isEqualTo("TriageRulePreviewResponse");
    }

    @Test
    void springdocExportedBusinessRoutesAreImplementedSubsetOfFrozenDesign() throws Exception {
        JsonNode frozen = frozenContract();
        String runtimeJson = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode runtime = objectMapper.readTree(runtimeJson);

        Set<String> exported = operations(runtime.path("paths"), "/api/v1");
        assertThat(exported).containsExactlyInAnyOrderElementsOf(IMPLEMENTED_OPERATIONS);
        for (String key : exported) {
            JsonNode frozenOperation = operation(frozen, key);
            assertThat(frozenOperation.isMissingNode()).as(key + " must exist in frozen design").isFalse();
            assertThat(frozenOperation.path("x-implementation-status").asText()).isEqualTo("IMPLEMENTED");
            String expectedStatus = key.equals("POST /auth/user/register")
                || key.equals("POST /admin/users")
                || key.equals("POST /admin/roles")
                || key.equals("POST /admin/menus")
                || key.equals("POST /files")
                || key.equals("POST /pets")
                || key.equals("POST /pets/{petId}/health-logs")
                || key.equals("POST /appointments")
                || key.equals("POST /appointments/{appointmentId}/change-requests")
                || key.equals("POST /staff/appointments/{appointmentId}/change-requests")
                || key.equals("POST /admin/doctors")
                || key.equals("POST /staff/health/guides")
                || key.equals("POST /staff/health/standards")
                || key.equals("POST /staff/triage-rules")
                || key.equals("POST /staff/knowledge/documents")
                || key.equals("POST /staff/knowledge/documents/{documentId}/versions")
                || key.equals("POST /staff/slots")
                ? "201"
                : "200";
            assertThat(frozenOperation.path("responses").has(expectedStatus)).as("%s returns %s", key, expectedStatus).isTrue();
        }
    }

    private JsonNode frozenContract() throws Exception {
        Path contract = Path.of("doc", "openapi.yaml");
        if (!Files.isRegularFile(contract)) {
            contract = Path.of("..", "doc", "openapi.yaml");
        }
        assertThat(contract).isRegularFile();
        return objectMapper.readTree(Files.readString(contract));
    }

    private static Set<String> operations(JsonNode paths, String prefixToRemove) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String apiPath = pathEntry.getKey();
            if (!prefixToRemove.isEmpty()) {
                if (!apiPath.startsWith(prefixToRemove + "/") && !apiPath.equals(prefixToRemove)) {
                    continue;
                }
                apiPath = apiPath.substring(prefixToRemove.length());
            }
            for (Map.Entry<String, JsonNode> methodEntry : pathEntry.getValue().properties()) {
                String method = methodEntry.getKey();
                if (HTTP_METHODS.contains(method)) {
                    assertThat(result.add(method.toUpperCase() + " " + apiPath))
                        .as("duplicate exported operation")
                        .isTrue();
                }
            }
        }
        return result;
    }

    private static JsonNode operation(JsonNode document, String key) {
        int separator = key.indexOf(' ');
        String method = key.substring(0, separator).toLowerCase();
        String apiPath = key.substring(separator + 1);
        return document.path("paths").path(apiPath).path(method);
    }
}
