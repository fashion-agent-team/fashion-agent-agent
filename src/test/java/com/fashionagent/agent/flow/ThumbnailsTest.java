package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;

/** 옷 부분 작은 사진(closet_save에 넘김). 사진은 메모리에서만 만듭니다. */
class ThumbnailsTest {

    private static String png(BufferedImage img) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static BufferedImage decode(String b64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
    }

    @Test
    @DisplayName("crop Given 가운데 빨간 옷 When 그 박스로 자르면 Then 긴 변 256px 이하 JPEG이고 빨간색이 남는다")
    void cropsBox() throws Exception {
        // given
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.setColor(Color.RED);
        g.fillRect(200, 150, 400, 300);
        g.dispose();

        // when
        String out = Thumbnails.crop(png(img), new Garment.Box(0.25, 0.25, 0.5, 0.5));

        // then
        BufferedImage thumb = decode(out);
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isLessThanOrEqualTo(Thumbnails.LONG_SIDE);
        Color mid = new Color(thumb.getRGB(thumb.getWidth() / 2, thumb.getHeight() / 2));
        assertThat(mid.getRed()).isGreaterThan(200);
        assertThat(mid.getGreen()).isLessThan(60);
    }

    @Test
    @DisplayName("crop Given 몇 KB인데 1만×1만 픽셀인 PNG When 자르면 Then 다 풀지 않고 건너뛰며 읽어 작은 사진을 만든다")
    void hugeDimensionsAreSubsampled() throws Exception {
        // given: 흑백 1비트라 만들 때도 12MB 정도, 압축하면 수십 KB
        BufferedImage huge = new BufferedImage(10_000, 10_000, BufferedImage.TYPE_BYTE_BINARY);
        String b64 = png(huge);
        assertThat(b64.length()).isLessThan(200_000);

        // when
        BufferedImage read = Thumbnails.read(Base64.getDecoder().decode(b64), Thumbnails.DECODE_MAX_SIDE);
        String out = Thumbnails.crop(b64, new Garment.Box(0.1, 0.1, 0.8, 0.8));

        // then: 10000 / 2048 = 4칸씩 건너뜀 → 2500px
        assertThat(read.getWidth()).isEqualTo(2500);
        assertThat(out).isNotNull();
    }

    @Test
    @DisplayName("crop Given 사진이 아닌 값이나 너무 작은 박스 When 자르면 Then null(저장 안 함)")
    void badInputs() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        assertThat(Thumbnails.crop("bm90LWFuLWltYWdl", new Garment.Box(0, 0, 1, 1))).isNull();
        assertThat(Thumbnails.crop(png(img), new Garment.Box(0.5, 0.5, 0.01, 0.01))).isNull();
        assertThat(Thumbnails.crop(null, new Garment.Box(0, 0, 1, 1))).isNull();
        assertThat(Thumbnails.crop(png(img), null)).isNull();
    }
}
