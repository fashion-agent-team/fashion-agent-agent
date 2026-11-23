package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.judge.Occasion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpokenOccasionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("말 속 자리를 찾는다: 결혼식·면접·장례식, 없으면 null")
    void fromUtterance() {
        assertThat(SpokenOccasion.from("결혼식에 뭐 입지?")).isEqualTo(new SpokenOccasion.Spoken(Occasion.wedding, "결혼식"));
        assertThat(SpokenOccasion.from("내일 면접인데 뭐 입을까")).isEqualTo(new SpokenOccasion.Spoken(Occasion.formal, "면접"));
        assertThat(SpokenOccasion.from("친구 장례식 가는데").occasion()).isEqualTo(Occasion.mourning);
        assertThat(SpokenOccasion.from("운동화 젖어?")).isNull();
        assertThat(SpokenOccasion.from("운동화 신고 운동 가").occasion()).isEqualTo(Occasion.active);
        assertThat(SpokenOccasion.from("내일 회사 가는데 뭐 입을까?").occasion()).isEqualTo(Occasion.business);
        assertThat(SpokenOccasion.from("오늘 뭐 입지?")).isNull();
    }

    @Test
    @DisplayName("일정이 없으면 말한 자리를 더하고, 추천 문장은 '일정은 없어요' 대신 말한 자리에 맞춘다고 한다")
    void mergeIntoEmpty() throws Exception {
        JsonNode merged = SpokenOccasion.merge(json.readTree("[]"), "wedding", "결혼식");
        assertThat(Occasion.strictest(merged)).isEqualTo(Occasion.wedding);
        assertThat(OutfitFlow.scheduleSentence(merged)).isEqualTo("말씀하신 결혼식에 맞춰 볼게요.");
    }

    @Test
    @DisplayName("일정이 있으면 일정도 이어서 읽는다")
    void mergeWithCasualEvent() throws Exception {
        JsonNode events = json.readTree("[{\"title\":\"친구 만남\",\"occasion\":\"casual\"}]");
        JsonNode merged = SpokenOccasion.merge(events, "formal", "면접");
        assertThat(Occasion.strictest(merged)).isEqualTo(Occasion.formal);
        assertThat(OutfitFlow.scheduleSentence(merged)).startsWith("말씀하신 면접에 맞춰 볼게요. 일정은 친구 만남이 있어요.");
    }

    @Test
    @DisplayName("일정에 이미 같거나 더 엄격한 자리가 있으면 그대로 둔다")
    void keepStricterEvent() throws Exception {
        JsonNode events = json.readTree("[{\"title\":\"친구 결혼식\",\"occasion\":\"wedding\"}]");
        assertThat(SpokenOccasion.merge(events, "wedding", "결혼식")).isSameAs(events);
        assertThat(SpokenOccasion.merge(events, null, null)).isSameAs(events);
    }

    @Test
    @DisplayName("일정을 못 불러와도 말한 자리에 맞춘다")
    void mergeIntoNull() {
        JsonNode merged = SpokenOccasion.merge(null, "mourning", "장례식");
        assertThat(OutfitFlow.scheduleSentence(merged)).isEqualTo("말씀하신 장례식에 맞춰 볼게요.");
    }
}
