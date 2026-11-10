package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileDtos.FileView;
import com.pethospital.shared.FileService;
import com.pethospital.shared.ObjectStorageAdapter;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag(TestTags.MYSQL_IT)
@Tag("real-s3")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "storage.s3.enabled=true"
})
class M0403IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 40_301;
    private static final long USER = 40_302;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FileService files;

    @Autowired
    ObjectStorageAdapter storage;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void resetFixtures() {
        cleanup();
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0403-admin',NULL,'文件管理员','ACTIVE',0,0),
                   (?,'m0403-user',NULL,'文件用户','ACTIVE',0,0)
            """, ADMIN, USER);
    }

    @AfterEach
    void cleanupAfterTest() {
        SecurityContextHolder.clearContext();
        cleanup();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @Test
    void imageUploadReencodesStoresReadyMetadataAndReplaysExactlyOnce() throws Exception {
        authenticate(ADMIN, "WEB", "ADMIN");
        byte[] original = withMarker(image("png"), "Private-Exif-Like-Marker");
        var file = upload("guide.png", "application/x-msdownload", original);

        FileView first = files.upload(file, "GUIDE", "m0403-image-upload-0001");
        FileView replay = files.upload(file, "GUIDE", "m0403-image-upload-0001");

        assertThat(replay).isEqualTo(first);
        assertThat(first.status()).isEqualTo("READY");
        assertThat(first.mimeType()).isEqualTo("image/png");
        assertThat(first.toString()).doesNotContain("objectKey", "quarantine/");
        String objectKey = jdbc.queryForObject(
            "SELECT object_key FROM file_object WHERE id=?", String.class, Long.parseLong(first.id())
        );
        byte[] stored = storage.get(objectKey);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
            .doesNotContain("Private-Exif-Like-Marker");
        assertThat(jdbc.queryForObject(
            "SELECT sha256 FROM file_object WHERE id=?", String.class, Long.parseLong(first.id())
        )).isEqualTo(sha256(stored));
        assertThat(files.detail(Long.parseLong(first.id()))).isEqualTo(first);
        assertThat(count("file_object", "uploader_user_id=" + ADMIN)).isEqualTo(1);
        assertThat(count("audit_log", "actor_user_id=" + ADMIN + " AND operation_key='m0403-image-upload-0001'"))
            .isEqualTo(1);
    }

    @Test
    void disguisedExecutableIsRejectedAndSameKeyReplaysRejectionWithoutS3Object() {
        authenticate(ADMIN, "WEB", "ADMIN");
        var disguised = upload(
            "guide.jpg",
            "image/jpeg",
            "MZ\u0000fake executable payload".getBytes(StandardCharsets.ISO_8859_1)
        );

        assertRejected(disguised, "GUIDE", "m0403-reject-upload-01", "UNSUPPORTED_FILE_TYPE");
        assertRejected(disguised, "GUIDE", "m0403-reject-upload-01", "UNSUPPORTED_FILE_TYPE");

        assertThat(count("file_object", "uploader_user_id=" + ADMIN + " AND status='REJECTED'"))
            .isEqualTo(1);
        assertThat(count("file_object", "uploader_user_id=" + ADMIN + " AND status='READY'"))
            .isZero();
    }

    @Test
    void safePdfIsReadyButActivePdfIsRejected() throws Exception {
        authenticate(ADMIN, "WEB", "ADMIN");
        byte[] safe = pdf();
        FileView ready = files.upload(
            upload("source.pdf", "application/octet-stream", safe),
            "KNOWLEDGE",
            "m0403-safe-pdf-0001"
        );
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.mimeType()).isEqualTo("application/pdf");

        byte[] active = withMarker(safe, "/JavaScript /JS (app.alert)");
        assertRejected(
            upload("active.pdf", "application/pdf", active),
            "KNOWLEDGE",
            "m0403-active-pdf-01",
            "PDF_ACTIVE_CONTENT"
        );
    }

    @Test
    void purposePermissionIsClosedByAudienceAndRole() throws Exception {
        authenticate(USER, "MINI", "USER");
        FileView avatar = files.upload(
            upload("avatar.jpg", "image/jpeg", image("jpeg")),
            "AVATAR",
            "m0403-user-avatar-01"
        );
        assertThat(avatar.status()).isEqualTo("READY");

        assertThatThrownBy(() -> files.upload(
            upload("knowledge.png", "image/png", image("png")),
            "KNOWLEDGE",
            "m0403-user-knowledge"
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_PURPOSE_FORBIDDEN")
        );

        authenticate(ADMIN, "WEB", "ADMIN");
        assertThatThrownBy(() -> files.upload(
            upload("pet.png", "image/png", image("png")),
            "PET",
            "m0403-admin-pet-01"
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("FILE_PURPOSE_FORBIDDEN")
        );
    }

    @Test
    void sameIdempotencyKeyWithDifferentBytesConflicts() throws Exception {
        authenticate(ADMIN, "WEB", "ADMIN");
        files.upload(upload("guide.png", "image/png", image("png")), "GUIDE", "m0403-conflict-key1");

        assertThatThrownBy(() -> files.upload(
            upload("different.png", "image/png", withMarker(image("png"), "different")),
            "GUIDE",
            "m0403-conflict-key1"
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("IDEMPOTENCY_CONFLICT")
        );
        assertThat(count("file_object", "uploader_user_id=" + ADMIN)).isEqualTo(1);
    }

    @Test
    void metadataRequiresUploaderOrRecordedBusinessPartyAndNeverReturnsObjectKey() throws Exception {
        authenticate(ADMIN, "WEB", "ADMIN");
        FileView uploaded = files.upload(
            upload("guide.png", "image/png", image("png")),
            "GUIDE",
            "m0403-detail-access1"
        );
        long fileId = Long.parseLong(uploaded.id());

        authenticate(USER, "MINI", "USER");
        assertThatThrownBy(() -> files.detail(fileId))
            .isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.code).isEqualTo("FILE_ACCESS_DENIED")
            );

        jdbc.update("""
            INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
            VALUES (940301,?,'GUIDE',777,?,?)
            """, fileId, USER, ADMIN);
        FileView visible = files.detail(fileId);
        assertThat(visible).isEqualTo(uploaded);
        assertThat(visible.toString()).doesNotContain("objectKey", "quarantine/");
    }

    @Test
    void multipartHttpEndpointsReturnStrictViewWithoutObjectKey() throws Exception {
        MockMultipartFile file = upload("guide.png", "image/png", image("png"));
        MockMultipartFile purpose = new MockMultipartFile(
            "purpose", "", "text/plain", "GUIDE".getBytes(StandardCharsets.UTF_8)
        );
        var auth = jwt()
            .jwt(builder -> builder
                .claim("userId", Long.toString(ADMIN))
                .claim("sessionId", "40301")
                .claim("roles", List.of("ADMIN"))
                .audience(List.of("WEB")))
            .authorities(
                new SimpleGrantedAuthority("AUD_WEB"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
            );

        String body = mockMvc.perform(multipart("/api/v1/files")
                .file(file)
                .file(purpose)
                .header("Idempotency-Key", "m0403-http-upload01")
                .with(auth))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.mimeType").value("image/png"))
            .andExpect(jsonPath("$.data.objectKey").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String fileId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(body).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/{fileId}", fileId).with(auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(fileId))
            .andExpect(jsonPath("$.data.objectKey").doesNotExist());
    }

    private void assertRejected(
        MockMultipartFile file,
        String purpose,
        String key,
        String code
    ) {
        assertThatThrownBy(() -> files.upload(file, purpose, key))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.code).isEqualTo(code);
                assertThat(error.status.value()).isEqualTo(422);
            });
    }

    private void cleanup() {
        SecurityContextHolder.clearContext();
        if (!tableReady()) {
            return;
        }
        jdbc.queryForList(
            "SELECT object_key FROM file_object WHERE uploader_user_id IN (?,?) AND status='READY'",
            String.class,
            ADMIN,
            USER
        ).forEach(key -> {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // Preserve database cleanup so a failed assertion does not contaminate later test cases.
            }
        });
        jdbc.update("DELETE FROM file_binding WHERE file_id IN (SELECT id FROM file_object WHERE uploader_user_id IN (?,?))", ADMIN, USER);
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?,?)", ADMIN, USER);
        jdbc.update("DELETE FROM file_object WHERE uploader_user_id IN (?,?)", ADMIN, USER);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?)", ADMIN, USER);
    }

    private boolean tableReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
    }

    private static void authenticate(long userId, String audience, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("m0403-test-token")
            .header("alg", "RS256")
            .subject(Long.toString(userId))
            .claim("userId", Long.toString(userId))
            .claim("sessionId", "40301")
            .claim("roles", List.of(role))
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(1))
            .expiresAt(now.plusSeconds(300))
            .build();
        var authentication = new JwtAuthenticationToken(
            jwt,
            List.of(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            )
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static MockMultipartFile upload(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private static byte[] pdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] withMarker(byte[] source, String marker) {
        byte[] suffix = marker.getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[source.length + suffix.length];
        System.arraycopy(source, 0, result, 0, source.length);
        System.arraycopy(suffix, 0, result, source.length, suffix.length);
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
