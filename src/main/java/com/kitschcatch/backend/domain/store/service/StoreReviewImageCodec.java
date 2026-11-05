// 이미지의 실제 형식과 크기를 검증하고 메타데이터 없는 표준 PNG로 변환한다.
package com.kitschcatch.backend.domain.store.service;

import com.kitschcatch.backend.global.exception.BusinessException;
import com.kitschcatch.backend.global.exception.ErrorCode;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class StoreReviewImageCodec {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of(
        "JPEG", "image/jpeg", "JPG", "image/jpeg", "PNG", "image/png", "WEBP", "image/webp");
    private final Semaphore slots = new Semaphore(2);

    public record Normalized(byte[] png, int width, int height) {}

    public Normalized normalize(byte[] source, String contentType) {
        if (source.length == 0 || source.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
        }
        if (!slots.tryAcquire()) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_BUSY);
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!contentType.equals(TYPES.get(reader.getFormatName().toUpperCase(Locale.ROOT)))) {
                    throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long) width * height > 12000000) {
                    throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
                }
                BufferedImage decoded = reader.read(0);
                double scale = Math.min(1.0, 1600.0 / Math.max(width, height));
                int targetWidth = Math.max(1, (int) Math.round(width * scale));
                int targetHeight = Math.max(1, (int) Math.round(height * scale));
                int orientation = orientation(source);
                boolean swapped = orientation >= 5;
                var clean = new BufferedImage(swapped ? targetHeight : targetWidth,
                    swapped ? targetWidth : targetHeight, BufferedImage.TYPE_INT_ARGB);
                var graphics = clean.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.transform(orientationTransform(orientation, targetWidth, targetHeight));
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                } finally {
                    graphics.dispose();
                    decoded.flush();
                }
                var output = new LimitedOutput();
                try {
                    if (!ImageIO.write(clean, "png", output)) {
                        throw new IOException("PNG writer unavailable");
                    }
                    return new Normalized(output.toByteArray(), clean.getWidth(), clean.getHeight());
                } finally {
                    clean.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
        } finally {
            slots.release();
        }
    }

    private static int orientation(byte[] bytes) throws IOException {
        try {
            var directory = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
                .getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer value = directory == null ? null : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return value == null || value < 1 || value > 8 ? 1 : value;
        } catch (ImageProcessingException exception) {
            throw new IOException("이미지 메타데이터 해석 실패", exception);
        }
    }

    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if ((long) count + length > MAX_BYTES) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            super.write(bytes, offset, length);
        }

        @Override
        public synchronized void write(int value) {
            if (count >= MAX_BYTES) {
                throw new BusinessException(ErrorCode.STORE_REVIEW_IMAGE_INVALID);
            }
            super.write(value);
        }
    }
}
