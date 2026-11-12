package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.judge.Occasion;

class ClosetCallsTest {

    @Test
    @DisplayName("imageQuery: 자리와 체감 온도로 짧은 영어 문장")
    void imageQuery() {
        assertThat(ClosetQuery.english(Occasion.business, 2.0))
                .isEqualTo("neat office attire, dress shirt and slacks, warm winter clothes, coat and knit sweater");
        assertThat(ClosetQuery.english(Occasion.active, 28.0)).isEqualTo("workout sportswear, light summer clothes, short sleeves");
        assertThat(ClosetQuery.english(null, null)).isEqualTo("casual everyday clothes");
    }

    @Test
    @DisplayName("closet_save: 벡터가 있는 옷에만 embedding, 하나라도 있으면 embeddingModel")
    void saveArgs() {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("id", "p1_g1", "category", "top"));
        Map<String, Object> b = new LinkedHashMap<>(Map.of("id", "p2_g1", "category", "bottom"));
        var args = OutfitFlow.saveArgs(List.of(a, b), Map.of("p1_g1", List.of(0.1, 0.2)), "marqo-fashionSigLIP");
        @SuppressWarnings("unchecked")
        var gs = (List<Map<String, Object>>) args.get("garments");
        assertThat(gs.get(0)).containsEntry("embedding", List.of(0.1, 0.2));
        assertThat(gs.get(1)).doesNotContainKey("embedding");
        assertThat(args).containsEntry("embeddingModel", "marqo-fashionSigLIP");
        assertThat(a).doesNotContainKey("embedding");

        var none = OutfitFlow.saveArgs(List.of(b), Map.of(), null);
        assertThat(none).doesNotContainKey("embeddingModel");
    }

    @Test
    @DisplayName("closet_save: 작은 사진이 있는 옷에만 thumbnail(벡터 없어도)")
    void saveArgsThumbnail() {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("id", "p1_g1", "category", "top"));
        Map<String, Object> b = new LinkedHashMap<>(Map.of("id", "p1_g2", "category", "bottom"));
        var args = OutfitFlow.saveArgs(List.of(a, b), Map.of(), null, Map.of("p1_g2", "AAAA"));
        @SuppressWarnings("unchecked")
        var gs = (List<Map<String, Object>>) args.get("garments");
        assertThat(gs.get(0)).doesNotContainKey("thumbnail");
        assertThat(gs.get(1)).containsEntry("thumbnail", "AAAA");
        assertThat(args).doesNotContainKey("embeddingModel");
    }

    @Test
    @DisplayName("작은 사진: box 부분만 잘라 긴 변 256px JPEG, 48KB 이하. box가 없거나 사진이 아니면 null")
    void thumbnail() throws Exception {
        var img = new java.awt.image.BufferedImage(1000, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var rnd = new java.util.Random(1);
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 1000; x++) {
                img.setRGB(x, y, rnd.nextInt(0xffffff));
            }
        }
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bytes);
        String photo = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());

        String t = Thumbnails.crop(photo, new com.fashionagent.agent.model.Garment.Box(0.1, 0.1, 0.5, 0.8));
        byte[] jpeg = java.util.Base64.getDecoder().decode(t);
        assertThat(jpeg.length).isLessThanOrEqualTo(48 * 1024);
        assertThat(jpeg[0] & 0xff).isEqualTo(0xff);
        assertThat(jpeg[1] & 0xff).isEqualTo(0xd8);
        var back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertThat(Math.max(back.getWidth(), back.getHeight())).isEqualTo(256);
        assertThat(back.getHeight()).isGreaterThan(back.getWidth());   // 세로로 긴 옷 부분

        assertThat(Thumbnails.crop(photo, null)).isNull();
        assertThat(Thumbnails.crop("not-an-image", new com.fashionagent.agent.model.Garment.Box(0, 0, 1, 1))).isNull();
    }
}
