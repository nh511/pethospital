package com.pethospital.shared;

import com.pethospital.shared.FileDtos.FilePurpose;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

@Component
public class FileContentValidator {
    static final long IMAGE_LIMIT = 5L * 1024 * 1024;
    static final long PDF_LIMIT = 10L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final int MAX_IMAGE_DIMENSION = 12_000;
    private static final int MAX_PDF_PAGES = 500;
    private static final Set<FilePurpose> PDF_PURPOSES = Set.of(
        FilePurpose.MEDICAL,
        FilePurpose.KNOWLEDGE
    );
    private static final Pattern ACTIVE_PDF_FEATURE = Pattern.compile(
        "(?i)/(JavaScript|JS|OpenAction|AA|Launch|EmbeddedFile|RichMedia|XFA|Encrypt|URI|SubmitForm|ImportData|GoToR)\\b"
    );

    private final Tika tika = new Tika();

    public Validation validate(FilePurpose purpose, String originalName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Validation.rejected("EMPTY_FILE", "文件不能为空", "application/octet-stream", 0);
        }
        if (bytes.length > PDF_LIMIT) {
            return Validation.rejected("FILE_TOO_LARGE", "文件超过10MB限制", "application/octet-stream", bytes.length);
        }
        String detected = detect(bytes, originalName);
        if (!extensionMatches(originalName, detected)) {
            return Validation.rejected(
                "FILE_EXTENSION_MISMATCH",
                "文件扩展名与实际类型不一致",
                detected,
                bytes.length
            );
        }
        return switch (detected) {
            case "image/jpeg" -> validateImage(bytes, detected, "jpeg");
            case "image/png" -> validateImage(bytes, detected, "png");
            case "application/pdf" -> validatePdf(purpose, bytes);
            default -> Validation.rejected(
                "UNSUPPORTED_FILE_TYPE",
                "只接受JPEG、PNG或允许用途的PDF文件",
                detected,
                bytes.length
            );
        };
    }

    private String detect(byte[] bytes, String originalName) {
        try {
            return tika.detect(bytes).toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return "application/octet-stream";
        }
    }

    private Validation validateImage(byte[] bytes, String mimeType, String format) {
        if (bytes.length > IMAGE_LIMIT) {
            return Validation.rejected("FILE_TOO_LARGE", "图片超过5MB限制", mimeType, bytes.length);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return Validation.rejected("INVALID_IMAGE", "图片无法解码", mimeType, bytes.length);
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Validation.rejected("INVALID_IMAGE", "图片无法解码", mimeType, bytes.length);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION
                    || height > MAX_IMAGE_DIMENSION || (long) width * height > MAX_IMAGE_PIXELS) {
                    return Validation.rejected("IMAGE_DIMENSIONS_UNSAFE", "图片尺寸过大", mimeType, bytes.length);
                }
                BufferedImage decoded = reader.read(0);
                BufferedImage safeImage = "jpeg".equals(format) ? toRgb(decoded) : decoded;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(safeImage, format, output)) {
                    return Validation.rejected("INVALID_IMAGE", "图片无法安全重编码", mimeType, bytes.length);
                }
                byte[] sanitized = output.toByteArray();
                if (sanitized.length > IMAGE_LIMIT) {
                    return Validation.rejected("FILE_TOO_LARGE", "重编码图片超过5MB限制", mimeType, sanitized.length);
                }
                return Validation.accepted(mimeType, sanitized);
            } finally {
                reader.dispose();
            }
        } catch (Exception exception) {
            return Validation.rejected("INVALID_IMAGE", "图片损坏或无法安全解码", mimeType, bytes.length);
        }
    }

    private Validation validatePdf(FilePurpose purpose, byte[] bytes) {
        if (!PDF_PURPOSES.contains(purpose)) {
            return Validation.rejected("PDF_PURPOSE_NOT_ALLOWED", "该文件用途不允许PDF", "application/pdf", bytes.length);
        }
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        if (ACTIVE_PDF_FEATURE.matcher(raw).find()) {
            return Validation.rejected("PDF_ACTIVE_CONTENT", "PDF包含加密、脚本、嵌入文件或活动动作", "application/pdf", bytes.length);
        }
        try (var document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            if (document.isEncrypted()) {
                return Validation.rejected("PDF_ENCRYPTED", "不接受加密PDF", "application/pdf", bytes.length);
            }
            if (pages <= 0 || pages > MAX_PDF_PAGES) {
                return Validation.rejected("PDF_PAGE_LIMIT", "PDF页数不在允许范围", "application/pdf", bytes.length);
            }
            if (containsActivePdfObject(
                document.getDocumentCatalog().getCOSObject(),
                Collections.newSetFromMap(new IdentityHashMap<>())
            )) {
                return Validation.rejected("PDF_ACTIVE_CONTENT", "PDF包含脚本、嵌入文件或活动动作", "application/pdf", bytes.length);
            }
            return Validation.accepted("application/pdf", bytes.clone());
        } catch (Exception exception) {
            return Validation.rejected("INVALID_PDF", "PDF损坏或无法安全解析", "application/pdf", bytes.length);
        }
    }

    private static boolean extensionMatches(String originalName, String mimeType) {
        String lower = originalName.toLowerCase(Locale.ROOT);
        return switch (mimeType) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "application/pdf" -> lower.endsWith(".pdf");
            default -> true;
        };
    }

    private static boolean containsActivePdfObject(COSBase value, Set<COSBase> visited) {
        if (value == null || !visited.add(value)) {
            return false;
        }
        if (value instanceof COSObject object) {
            return containsActivePdfObject(object.getObject(), visited);
        }
        if (value instanceof COSArray array) {
            for (int index = 0; index < array.size(); index++) {
                if (containsActivePdfObject(array.get(index), visited)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof COSDictionary dictionary) {
            for (COSName key : dictionary.keySet()) {
                String name = key.getName();
                if (Set.of(
                    "JavaScript", "JS", "OpenAction", "AA", "Launch", "EmbeddedFile",
                    "RichMedia", "XFA", "URI", "SubmitForm", "ImportData", "GoToR"
                ).contains(name)) {
                    return true;
                }
                if (containsActivePdfObject(dictionary.getItem(key), visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    public record Validation(
        boolean accepted,
        String mimeType,
        byte[] bytes,
        long observedSize,
        String rejectionCode,
        String rejectionMessage
    ) {
        static Validation accepted(String mimeType, byte[] bytes) {
            return new Validation(true, mimeType, bytes, bytes.length, null, null);
        }

        static Validation rejected(String code, String message, String mimeType, long size) {
            return new Validation(false, mimeType, null, size, code, message);
        }
    }
}
