package com.fashionagent.agent.flow;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.fashionagent.agent.model.Garment;

/**
 * 옷 부분만 잘라 작게 줄인 JPEG(base64). closet_save에 넘겨 옷장 서버 DB에만 저장하고, 같은 옷이 애매할 때 GPT 비교에 씁니다.
 * 2026-11-12 박창근 님 결정: 원본 사진은 저장하지 않고, 옷 부분 작은 사진(얼굴 없음)만 내부 DB에.
 */
final class Thumbnails {

    static final int LONG_SIDE = 256;
    static final int MAX_BYTES = 48 * 1024;
    /**
     * 사진을 풀 때 긴 변 기준. 이보다 2배 이상 큰 사진은 건너뛰며 읽습니다. 몇 KB짜리 PNG가 4만×4만 픽셀이면
     * 그대로 풀 때 수 GB가 들어 agent(메모리 448MB)가 멈추는 것을 막습니다. 폰 사진(4032px까지)은 그대로 읽습니다.
     */
    static final int DECODE_MAX_SIDE = 2048;

    private Thumbnails() {
    }

    /** 사진에서 box(0~1) 부분을 3% 여유를 두고 잘라 긴 변 256px JPEG로. 못 만들면 null. */
    static String crop(String imageBase64, Garment.Box box) {
        if (imageBase64 == null || box == null || box.w() <= 0 || box.h() <= 0) {
            return null;
        }
        try {
            String s = imageBase64;
            int comma = s.indexOf(',');
            if (s.startsWith("data:") && comma > 0) {
                s = s.substring(comma + 1);
            }
            BufferedImage img = read(Base64.getMimeDecoder().decode(s), DECODE_MAX_SIDE);
            if (img == null) {
                return null;
            }
            int W = img.getWidth(), H = img.getHeight();
            double px = 0.03 * box.w(), py = 0.03 * box.h();
            int x0 = clamp((box.x() - px) * W, W), y0 = clamp((box.y() - py) * H, H);
            int x1 = clamp((box.x() + box.w() + px) * W, W), y1 = clamp((box.y() + box.h() + py) * H, H);
            if (x1 - x0 < 8 || y1 - y0 < 8) {
                return null;
            }
            BufferedImage part = img.getSubimage(x0, y0, x1 - x0, y1 - y0);
            double scale = Math.min(1.0, (double) LONG_SIDE / Math.max(part.getWidth(), part.getHeight()));
            int w = Math.max(1, (int) Math.round(part.getWidth() * scale)), h = Math.max(1, (int) Math.round(part.getHeight() * scale));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(part, 0, 0, w, h, null);
            g.dispose();
            for (float q : new float[] {0.8f, 0.6f, 0.4f}) {
                byte[] jpeg = jpeg(out, q);
                if (jpeg.length <= MAX_BYTES) {
                    return Base64.getEncoder().encodeToString(jpeg);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 크기를 먼저 읽고, 긴 변이 maxSide의 2배 이상이면 건너뛰며(서브샘플링) 읽습니다. 못 읽으면 null. */
    static BufferedImage read(byte[] bytes, int maxSide) throws java.io.IOException {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int longSide = Math.max(reader.getWidth(0), reader.getHeight(0));
                javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longSide / maxSide);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int clamp(double v, int max) {
        return (int) Math.max(0, Math.min(max, Math.round(v)));
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws java.io.IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
