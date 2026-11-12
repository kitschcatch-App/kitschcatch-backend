// 실제 이미지 바이트의 형식, 해상도, 회전 보정과 메타데이터 제거를 검증한다.
package com.kitschcatch.backend.domain.store.service;

import static org.assertj.core.api.Assertions.*;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.kitschcatch.backend.global.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreReviewImageCodecTest {
    private final StoreReviewImageCodec codec = new StoreReviewImageCodec();

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void decodesAndResizesRealImages(String format) throws Exception {
        var result = codec.normalize(image(2000, 1000, format), "image/" + format);
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(800);
        var actual = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertThat(actual.getWidth()).isEqualTo(1600);
        assertThat(actual.getHeight()).isEqualTo(800);
        assertThat(result.png()).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
    }

    @Test
    void decodesWebpThroughInstalledProvider() {
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        var result = codec.normalize(webp, "image/webp");
        assertThat(result.width()).isEqualTo(1);
        assertThat(result.height()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void appliesExifOrientationAndRemovesMetadata(int orientation) throws Exception {
        byte[] jpeg = image(80, 40, "jpeg");
        byte[] exif = HexFormat.of().parseHex("45786966000049492a0008000000010012010300010000000100000000000000");
        exif[24] = (byte) orientation;
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpeg, 0, 2);
        var data = new DataOutputStream(bytes);
        data.writeShort(0xffe1);
        data.writeShort(exif.length + 2);
        data.write(exif);
        data.write(jpeg, 2, jpeg.length - 2);
        var result = codec.normalize(bytes.toByteArray(), "image/jpeg");
        assertThat(result.width()).isEqualTo(orientation >= 5 ? 40 : 80);
        assertThat(result.height()).isEqualTo(orientation >= 5 ? 80 : 40);
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(result.png()))
            .getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        var decoded = ImageIO.read(new ByteArrayInputStream(result.png()));
        // 원본의 좌측 빨강/우측 파랑이 EXIF 변환 뒤 올바른 위치에 남는지 확인한다.
        int x = orientation >= 5 ? 20 : 10;
        int y = orientation >= 5 ? 10 : 20;
        int color = decoded.getRGB(x, y);
        boolean expectsRed = orientation == 1 || orientation == 4 || orientation == 5 || orientation == 6;
        assertThat((color >> 16 & 255) > (color & 255)).isEqualTo(expectsRed);
    }

    @Test
    void rejectsForgedMimeCorruptionOversizedDimensionsAndBytes() throws Exception {
        assertThatThrownBy(() -> codec.normalize(image(3, 2, "png"), "image/jpeg"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.normalize(new byte[] {1, 2, 3}, "image/png"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.normalize(image(8193, 1, "png"), "image/png"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.normalize(image(4001, 3000, "png"), "image/png"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.normalize(new byte[5242881], "image/png"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.normalize(new byte[0], "image/png"))
            .isInstanceOf(BusinessException.class);
    }

    private byte[] image(int width, int height, String format) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED); graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        image.flush();
        return output.toByteArray();
    }
}
