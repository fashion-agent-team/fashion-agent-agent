package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.InMemorySessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 빠른 색 확인("이거 무슨 색이야?"). clothes_inspect 결과는 가짜로 정해 두고, 읽어 주는 말·카메라 유지·끝내기를 본다. */
class ColorFlowTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T07:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private String inspectJson;
    private boolean fail;
    private InMemorySessionStore sessions;
    private ColorFlow flow;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        AgentTools tools = (tool, args) -> {
            calls.add(args);
            if (ToolNames.OUTFIT_COLOR_CHECK.equals(tool)) {
                return json.readTree("""
                        {"score":88,"scoreReason":"기본색에 강한 색 하나라 포인트가 있어요."}""");
            }
            assertThat(tool).isEqualTo(ToolNames.CLOTHES_INSPECT);
            if (fail) {
                throw new ToolException(tool, "upstream_failed", "모델 서버가 꺼짐");
            }
            return json.readTree(inspectJson);
        };
        sessions = new InMemorySessionStore(Duration.ofMinutes(30), clock);
        flow = new ColorFlow(tools, sessions, json);
        session = new AgentSession("s1", "u1");
    }

    private Garment garment(String body) {
        return json.treeToValue(json.readTree(body), Garment.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"무슨색", "어떤색", "색깔뭐야", "색알려줘", "무슨무늬야"})
    @DisplayName("공백을 뺀 색 질문은 색 확인으로 본다")
    void colorQuestions(String compact) {
        assertThat(ColorFlow.isColorQuestion(compact)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"내일뭐입지", "날씨어때", "옷장확인해줘"})
    @DisplayName("색과 상관없는 말은 색 확인이 아니다")
    void notColorQuestions(String compact) {
        assertThat(ColorFlow.isColorQuestion(compact)).isFalse();
    }

    @Test
    @DisplayName("Given 새 세션 When 시작 Then colorCheck를 켜고 카메라를 열며 세션을 저장한다")
    void start() {
        TurnResponse res = flow.start(session);

        assertThat(session.colorCheck).isTrue();
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
        assertThat(res.speech()).isEqualTo(ColorFlow.START);
        assertThat(sessions.find("u1", "s1")).isPresent();
    }

    @Test
    @DisplayName("Given 색 확인 중 When \"됐어\" Then 끝내고 done")
    void finish() {
        flow.start(session);

        TurnResponse res = flow.answer(session, "이제 됐어!");

        assertThat(session.colorCheck).isFalse();
        assertThat(res.next()).isEqualTo(Next.done);
        assertThat(res.speech()).isEqualTo(ColorFlow.DONE);
    }

    @Test
    @DisplayName("Given 색 확인 중 When 또 색을 물으면 Then 계속 카메라")
    void askAgain() {
        flow.start(session);

        TurnResponse res = flow.answer(session, "이건 무슨 색이야?");

        assertThat(session.colorCheck).isTrue();
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    @DisplayName("Given 색 확인 중 When \"이건?\"·\"짧게 말해줘\" Then 카메라를 켜 둔 채 답한다")
    void shortFollowUps() {
        flow.start(session);

        assertThat(flow.answer(session, "이건?").next()).isEqualTo(Next.camera_outfit);
        TurnResponse shorter = flow.answer(session, "짧게 말해줘");
        assertThat(shorter.next()).isEqualTo(Next.camera_outfit);
        assertThat(shorter.colorDetail()).isFalse();
        assertThat(session.colorCheck).isTrue();
    }

    @Test
    @DisplayName("Given 색 확인 중 When 다른 말(\"내일 날씨 어때\") Then 색 확인을 접고 null(원래 대화로)")
    void otherTopic() {
        flow.start(session);

        assertThat(flow.answer(session, "내일 날씨 어때")).isNull();
        assertThat(session.colorCheck).isFalse();
    }

    @Test
    @DisplayName("Given 흰 셔츠 한 벌 When 사진 Then \"흰색 셔츠예요.\"와 다음 옷 안내, 카메라 유지")
    void oneGarment() {
        inspectJson = """
                {"lighting":"ok","garments":[{"id":"g1","category":"top","name":"셔츠","colorName":"흰색"}]}""";

        TurnResponse res = flow.check(session, "jpeg");

        assertThat(res.speech()).startsWith("흰색 셔츠예요.").endsWith(ColorFlow.MORE);
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    @DisplayName("Given GPT 동의 여부 When 사진 Then allowGpt를 동의한 때만 true로 넘긴다")
    void passesConsent() {
        inspectJson = """
                {"lighting":"ok","garments":[{"id":"g1","category":"top","name":"셔츠","colorName":"흰색"}]}""";

        flow.check(session, "jpeg");
        session.gptConsent = true;
        flow.check(session, "jpeg");

        assertThat(calls).extracting(a -> a.get("allowGpt")).containsExactly(false, true);
        assertThat(calls.getFirst()).containsEntry("imageBase64", "jpeg");
    }

    @Test
    @DisplayName("Given 두 벌 When 사진 Then 위치를 붙여 \"왼쪽은 …, 오른쪽은 …예요.\"")
    void twoGarments() {
        List<Garment> garments = List.of(
                garment("""
                        {"id":"g1","category":"top","name":"셔츠","colorName":"흰색","position":"left"}"""),
                garment("""
                        {"id":"g2","category":"bottom","name":"바지","colorName":"검정","position":"right"}"""));

        assertThat(ColorFlow.speech(garments)).isEqualTo("왼쪽은 흰색 셔츠, 오른쪽은 검정 바지예요.");
    }

    @Test
    @DisplayName("Given 색을 모르는 옷 When 읽으면 Then 종류만 말하고 \"색은 잘 모르겠어요\"(이에요를 덧붙이지 않음)")
    void unknownColor() {
        Garment g = garment("""
                {"id":"g1","category":"top","name":"셔츠"}""");

        assertThat(ColorFlow.speech(List.of(g))).isEqualTo("셔츠인데 색은 잘 모르겠어요.");
    }

    @Test
    @DisplayName("Given 무늬를 확실히 모르고 1등이 stripe When 한 벌 Then \"무늬는 확실하지 않은데 줄무늬 같아요\"")
    void patternGuess() {
        Garment g = garment("""
                {"id":"g1","category":"top","name":"셔츠","colorName":"남색","patternGuess":"stripe"}""");

        assertThat(ColorFlow.speech(List.of(g))).isEqualTo("남색 셔츠예요. 무늬는 확실하지 않은데 줄무늬 같아요.");
    }

    @Test
    @DisplayName("Given 얼룩이 있는 옷 When 읽으면 Then 색 뒤에 얼룩이 있어 보인다고 덧붙인다")
    void mentionsDefects() {
        Garment g = garment("""
                {"id":"g1","category":"top","name":"티셔츠","colorName":"회색","defects":[{"kind":"stain","confidence":0.9}]}""");

        assertThat(ColorFlow.speech(List.of(g))).startsWith("회색 티셔츠예요.").contains("얼룩이나 찢어진 곳");
    }

    @Test
    @DisplayName("Given 어두운 사진 When 사진 Then 밝은 곳에서 다시 비춰 달라고 하고 색은 말하지 않는다")
    void dark() {
        inspectJson = """
                {"lighting":"dark","garments":[{"id":"g1","category":"top","name":"셔츠","colorName":"검정"}]}""";

        TurnResponse res = flow.check(session, "jpeg");

        assertThat(res.speech()).contains("어두워요").doesNotContain("검정");
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    @DisplayName("Given 옷이 안 잡힘 When 사진 Then 옷 위로 폰을 들어 달라고 한다")
    void noGarments() {
        inspectJson = """
                {"lighting":"ok","garments":[]}""";

        assertThat(flow.check(session, "jpeg").speech()).contains("옷이 잘 안 보여요");
    }

    @Test
    @DisplayName("Given 옷 상태 서버 실패 When 사진 Then 예외 없이 다시 비춰 달라고 하고 카메라 유지")
    void toolFails() {
        fail = true;

        TurnResponse res = flow.check(session, "jpeg");

        assertThat(res.speech()).contains("한 번 더 비춰");
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    @DisplayName("이미 \"요\"로 끝나면 \"예요\"를 붙이지 않고, 받침 따라 이에요/예요")
    void ieyo() {
        assertThat(ColorFlow.ieyo("셔츠")).isEqualTo("셔츠예요");
        assertThat(ColorFlow.ieyo("청바지 한 벌")).isEqualTo("청바지 한 벌이에요");
        assertThat(ColorFlow.ieyo("모르겠어요")).isEqualTo("모르겠어요");
    }

    @Test
    @DisplayName("Given 색 확인으로 두 벌을 봄 When \"이 두 개 어울려?\" Then 어울림 점수, 카메라 유지")
    void harmonyOfTwo() {
        flow.start(session);
        assertThat(flow.answer(session, "이 두 개 어울려?").speech()).contains("두 벌 이상");
        inspectJson = """
                {"lighting":"ok","garments":[{"id":"g1","category":"top","name":"티셔츠","colorName":"주황","colorFamily":"orange"}]}""";
        flow.check(session, "jpeg");
        inspectJson = """
                {"lighting":"ok","garments":[{"id":"g1","category":"outer","name":"자켓","colorName":"갈색","colorFamily":"brown"}]}""";
        flow.check(session, "jpeg");

        TurnResponse res = flow.answer(session, "이 두 개 어울려?");

        assertThat(res.speech()).isEqualTo("주황 티셔츠와 갈색 자켓은 어울림 88점이에요. 기본색에 강한 색 하나라 포인트가 있어요.");
        assertThat(res.next()).isEqualTo(Next.camera_outfit);
        assertThat(session.colorCheck).isTrue();
    }
}
