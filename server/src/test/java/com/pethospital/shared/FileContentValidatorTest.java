package com.pethospital.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.pethospital.shared.FileDtos.FilePurpose;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class FileContentValidatorTest {
    private final FileContentValidator validator = new FileContentValidator();

    @Test
    void jpegIsDecodedAndReencodedWithoutTrailingMetadata() throws Exception {
        byte[] jpeg = image("jpeg");
        byte[] marker = "Exif-Private-Marker".getBytes(StandardCharsets.UTF_8);
        byte[] withMarker = new byte[jpeg.length + marker.length];
        System.arraycopy(jpeg, 0, withMarker, 0, jpeg.length);
        System.arraycopy(marker, 0, withMarker, jpeg.length, marker.length);

        var result = validator.validate(FilePurpose.AVATAR, "avatar.jpg", withMarker);

        assertThat(result.accepted()).isTrue();
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1))
            .doesNotContain("Exif-Private-Marker");
    }

    @Test
    void pngIsAcceptedAndExecutableDisguisedAsJpegIsRejected() throws Exception {
        assertThat(validator.validate(FilePurpose.PET, "pet.png", image("png")).accepted()).isTrue();

        var wrongExtension = validator.validate(FilePurpose.PET, "pet.jpg", image("png"));
        assertThat(wrongExtension.accepted()).isFalse();
        assertThat(wrongExtension.rejectionCode()).isEqualTo("FILE_EXTENSION_MISMATCH");

        var disguised = validator.validate(
            FilePurpose.PET,
            "pet.jpg",
            "MZ\u0000\u0002fake-executable".getBytes(StandardCharsets.ISO_8859_1)
        );
        assertThat(disguised.accepted()).isFalse();
        assertThat(disguised.rejectionCode()).isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void safePdfIsLimitedToClinicalAndKnowledgePurposes() throws Exception {
        byte[] pdf = pdf();

        assertThat(validator.validate(FilePurpose.MEDICAL, "record.pdf", pdf).accepted()).isTrue();
        assertThat(validator.validate(FilePurpose.KNOWLEDGE, "source.pdf", pdf).accepted()).isTrue();
        assertThat(validator.validate(FilePurpose.AVATAR, "avatar.pdf", pdf).rejectionCode())
            .isEqualTo("PDF_PURPOSE_NOT_ALLOWED");
    }

    @Test
    void activePdfFeatureIsRejectedEvenWhenParserCanOpenFile() throws Exception {
        byte[] pdf = pdf();
        byte[] active = "\n/JavaScript /JS (app.alert)\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] combined = new byte[pdf.length + active.length];
        System.arraycopy(pdf, 0, combined, 0, pdf.length);
        System.arraycopy(active, 0, combined, pdf.length, active.length);

        var result = validator.validate(FilePurpose.KNOWLEDGE, "active.pdf", combined);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionCode()).isEqualTo("PDF_ACTIVE_CONTENT");
    }

    @Test
    void hardSizeLimitRejectsBeforeParsing() {
        byte[] oversized = new byte[(int) FileContentValidator.PDF_LIMIT + 1];

        assertThat(validator.validate(FilePurpose.KNOWLEDGE, "huge.pdf", oversized).rejectionCode())
            .isEqualTo("FILE_TOO_LARGE");
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
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
}
