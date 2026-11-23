package com.fashionagent.agent.flow;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.assistant.Assistant;
import com.fashionagent.agent.judge.Korean;
import com.fashionagent.agent.api.TurnRequest;
import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;

/** 대화 한 번을 어느 흐름으로 보낼지 정합니다. */
@Service
public class TurnService {

    private final OutfitFlow outfit;
    private final ColorFlow color;
    private final FaceFlow face;
    private final ReminderFlow reminder;
    private final ClosetFlow closetFlow;
    private final Assistant assistant;
    private final AgentTools tools;
    private final SessionStore sessions;
    private final java.time.Clock clock;

    public TurnService(OutfitFlow outfit, ColorFlow color, FaceFlow face, ReminderFlow reminder, ClosetFlow closetFlow,
            Assistant assistant, AgentTools tools, SessionStore sessions, java.time.Clock clock) {
        this.closetFlow = closetFlow;
        this.outfit = outfit;
        this.color = color;
        this.face = face;
        this.reminder = reminder;
        this.assistant = assistant;
        this.tools = tools;
        this.sessions = sessions;
        this.clock = clock;
    }

    public TurnResponse handle(String userId, TurnRequest req) {
        return handle(userId, req, null);
    }

    /** @param wearer 게이트웨이가 바디 wearer에 넣은 내 정보. 없으면 null이고 그대로 둡니다. */
    public TurnResponse handle(String userId, TurnRequest req, JsonNode wearer) {
        AgentSession found = req.sessionId() == null ? null
                : sessions.find(userId, req.sessionId()).orElse(null);
        boolean inOutfit = found != null && (found.advice != null || !found.seen.isEmpty());
        AgentSession session = found != null ? found : new AgentSession("s_" + UUID.randomUUID(), userId);
        // 회원정보를 바꾸면 다음 말부터 바로 쓰이게 매번 새 값으로 바꿉니다.
        session.wearer = wearer;
        // "뭐라고?", "다시 말해 줘": 지난 말을 다시 읽어 줍니다. 2026-11-23 시뮬: "할 수 없는 일이에요"로 답했음.
        if (!req.hasImage() && isRepeatRequest(req.utterance()) && session.lastSpeech != null) {
            return new TurnResponse(session.sessionId, "다시 말씀드릴게요. " + session.lastSpeech,
                    session.lastNext != null ? session.lastNext : Next.listen, null);
        }
        TurnResponse res = turn(session, req, inOutfit);
        // 다음 턴에 카메라를 보던 중인지 알 수 있게 이번 next를 기억합니다.
        if (res.next() != session.lastNext || (res.speech() != null && !res.speech().equals(session.lastSpeech))) {
            session.lastNext = res.next();
            if (res.speech() != null) {
                session.lastSpeech = res.speech();
            }
            sessions.save(session);
        }
        return res;
    }

    private static final java.util.List<String> REPEAT_WORDS = java.util.List.of("다시말", "다시얘기", "다시읽어",
            "한번더말", "뭐라고", "뭐라구", "뭐라했", "못들었", "잘못들", "안들려", "못알아들");

    /** "뭐라고?", "다시 말해 줘", "못 들었어"처럼 지난 말을 다시 해 달라는 짧은 말. */
    static boolean isRepeatRequest(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        return s.length() <= 10 && REPEAT_WORDS.stream().anyMatch(s::contains);
    }

    /** "봐 줘", "봐 주세요", "확인해 줘"처럼 짧게 봐 달라는 말. */
    static boolean isLookRequest(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        return s.length() <= 7 && (s.startsWith("봐줘") || s.startsWith("봐주") || s.startsWith("확인해줘")
                || s.startsWith("옷봐줘"));
    }

    /** 짧은 대답. "어디야?"가 걸리지 않게 한 글자 말은 똑같을 때만 봅니다. */
    private static final java.util.List<String> ACK_EXACT = java.util.List.of("응", "어", "네", "예", "넵", "응응", "네네",
            "오케이", "그래");
    private static final java.util.List<String> ACK_PREFIX = java.util.List.of("알겠", "알았", "잠깐", "잠시만", "기다려",
            "준비됐", "비출게", "비춰볼게", "보여줄게", "다입었", "입었어", "입었다", "다했어");

    /** 카메라를 켠 채 그만두자는 말이면 true. 옷 카메라의 "그만", "됐어"는 옷 마치기(FINISH)가 먼저 가져갑니다. */
    static boolean cameraCancel(AgentSession session, String utterance) {
        Next cam = session.lastNext;
        if (cam != Next.camera_outfit && cam != Next.camera_worn && cam != Next.camera_face
                && cam != Next.camera_teeth) {
            return false;
        }
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (s.matches("(취소|취소해|취소할래|그만할래|안할래|나중에할래|나중에|카메라꺼|카메라꺼줘|꺼줘|끌래|안볼래)(줘|요)?")) {
            return true;
        }
        return (cam == Next.camera_face || cam == Next.camera_teeth) && s.matches("(그만|됐어|그만해|됐어요|그만해줘)");
    }

    /** 카메라를 켜 둔 채 짧게 대답만 했으면 카메라를 이어 갈 말, 아니면 null. */
    static String cameraWaitSpeech(AgentSession session, String utterance) {
        Next cam = session.lastNext;
        if (cam != Next.camera_outfit && cam != Next.camera_worn && cam != Next.camera_face
                && cam != Next.camera_teeth) {
            return null;
        }
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (s.isEmpty() || s.length() > 6) {
            return null;
        }
        if (cam == Next.camera_worn && java.util.List.of("뒤도", "뒤로", "뒷모습", "돌았", "돌게", "돌아").stream().anyMatch(s::contains)) {
            return "네, 뒤로 돌아서 그대로 서 계시면 찍을게요.";
        }
        if (!ACK_EXACT.contains(s) && ACK_PREFIX.stream().noneMatch(s::startsWith)) {
            return null;
        }
        return switch (cam) {
            case camera_teeth -> "네, 입을 '이' 하고 벌린 채 폰을 입 가까이 비춰 주세요. 보이면 바로 알려 드릴게요.";
            case camera_face -> "네, 폰을 얼굴 앞에 들고 비춰 주세요. 보이면 바로 알려 드릴게요.";
            case camera_worn -> "네, 두세 걸음 떨어져 서 주시면 찍을게요.";
            default -> "네, 옷을 폰으로 비춰 주세요. 보이면 바로 알려 드릴게요.";
        };
    }

    /** 날씨·일정·옷 추천을 답했으면 무엇을 어느 날로 물었는지 남깁니다("모레는?"에 이어 답하려고). */
    private TurnResponse remember(AgentSession session, String topic, String utterance, TurnResponse res) {
        session.lastTopic = topic;
        var d = SpokenDate.from(utterance, today());
        session.lastTopicDate = d == null || d.equals(today()) ? null : d.toString();
        sessions.save(session);
        return res;
    }

    private java.time.LocalDate today() {
        return java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
    }

    /** 이어 묻는 말이면 {주제, 다시 쓴 말("11월 25일 날씨")}, 아니면 null. */
    String[] followUp(AgentSession session, String utterance, Intent intent) {
        if (utterance == null) {
            return null;
        }
        String s = utterance.replaceAll("[\\s?.!,~]+", "");
        var today = today();
        var day = SpokenDate.from(s, today);
        // "나 오늘 면접이야" → "뭐 입고 가면 좋을까?": 자리·날을 이어 받아 추천(2026-11-23 시뮬: 면접을 잊고 편한 옷 추천).
        // "토요일에 데이트 있어"처럼 자리 말이 없어도 날과 계획만 말했으면 그 날을 이어 받습니다.
        // "내일 출근하는데 비 와?" → "그럼 뭐 입지?"도 출근에 맞춰(2026-11-23 시뮬).
        if (intent == Intent.WEATHER && SpokenOccasion.from(utterance) != null) {
            session.statedPlan = utterance;
        } else if (intent == Intent.OTHER && !s.contains("일정") && (SpokenOccasion.from(utterance) != null
                || (day != null && s.length() <= 15 && s.matches(".*(있어|있음|이야|예요|가|가요|가야돼|간다|가는데|해|해요)$")))) {
            session.statedPlan = utterance;
        } else if (intent == Intent.OUTFIT && session.statedPlan != null && day == null
                && SpokenOccasion.from(utterance) == null) {
            String plan = session.statedPlan;
            session.statedPlan = null;
            return new String[] {"outfit", plan + " " + utterance};
        } else if (intent != Intent.OTHER && intent != Intent.WEATHER) {
            session.statedPlan = null;
        }
        // "어제 날씨 어땠어?"처럼 지난 날을 말했으면 이어 묻기가 아닙니다.
        if (session.lastTopic == null || pastLabel(utterance) != null) {
            return null;
        }
        // 날짜 말과 "은/는/요" 말고 다른 말이 있으면("내일 3시 치과") 이어 묻기가 아닙니다(GPT가 일정 등록 등으로 봄).
        String rest = s.replaceAll("그럼|그러면|다다음주|다음주|이번주|담주|오늘|내일|모레|글피|[월화수목금토일]요일"
                + "|\\d{1,2}월|\\d{1,2}일|\\d{1,2}/\\d{1,2}", "").replaceAll("[은는요도]", "");
        if (intent == Intent.OTHER && day != null && rest.isEmpty()) {
            String date = day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
            return new String[] {session.lastTopic, switch (session.lastTopic) {
                case "weather" -> date + " 날씨";
                case "schedule" -> date + " 일정";
                default -> date + " 뭐 입지";
            }};
        }
        // "제주 내일 날씨" 뒤 "거기 우산 필요해?", "서울은?": 2026-11-23 시뮬에서 내 위치(서울) 날씨로 답하거나 GPT로 갔음.
        if ("weather".equals(session.lastTopic)) {
            String prefix = session.lastTopicDate == null ? "" : java.time.LocalDate.parse(session.lastTopicDate)
                    .getMonthValue() + "월 " + java.time.LocalDate.parse(session.lastTopicDate).getDayOfMonth() + "일 ";
            Cities.City named = Cities.in(utterance);
            // "부산 날씨" 뒤 "여기는?": 내 위치로 같은 날 날씨.
            if (intent == Intent.OTHER && session.lastCity != null && s.matches("(그럼|그러면)?(여기|이곳|우리동네|이동네)(는|은|요|는요|도)?")) {
                return new String[] {"weather", prefix + "날씨"};
            }
            if (intent == Intent.OTHER && named != null && day == null
                    && s.replaceAll("^(그럼|그러면)", "").length() <= named.name().length() + 3) {
                return new String[] {"weather", named.name() + " " + prefix + "날씨"};
            }
            // "치마 입어도 돼?" 뒤 "흰 원피스는?", "레깅스는?": 같은 날 그 옷을 입어도 되는지(2026-11-23 시뮬).
            if (intent == Intent.OTHER && named == null && ClosetView.category(s) != null
                    && s.replaceAll("(은|는|요|도|은요|는요)$", "").length() <= 8) {
                String garment = utterance.replaceAll("[?？.!~]", "").trim().replaceAll("(은|는|도)(요)?$", "");
                return new String[] {"weather", (session.lastCity == null ? "" : session.lastCity + " ") + prefix
                        + garment + " 입어도 돼?"};
            }
            if (intent == Intent.WEATHER && named == null && session.lastCity != null
                    && !s.matches(".*(여기|우리동네|이동네|내위치|집).*")) {
                return new String[] {"weather", session.lastCity + " " + (day == null ? prefix : "") + utterance};
            }
        }
        // "내일 뭐 입지?" 뒤 "치마 입어도 돼?": 오늘이 아니라 그날 날씨로 답합니다.
        // "장화 신어야 돼?", "추워?"도 마찬가지(날을 안 말했고 "지금"도 아니면 방금 말한 날).
        if (intent == Intent.WEATHER && day == null && session.lastTopicDate != null && !s.contains("지금")
                && !s.contains("밖에") && Cities.in(utterance) == null
                && ("outfit".equals(session.lastTopic) || "weather".equals(session.lastTopic))) {
            var d = java.time.LocalDate.parse(session.lastTopicDate);
            return new String[] {"weather", d.getMonthValue() + "월 " + d.getDayOfMonth() + "일 " + utterance};
        }
        // 추천 뒤 "다른 거", "딴 거": "다른 거 추천해 줘"처럼 앞서 말한 날·자리로 다시 추천(2026-11-23 시뮬에서 GPT로 갔음).
        if (intent == Intent.OTHER && "outfit".equals(session.lastTopic)
                && s.matches("(그럼|그러면|아니)?(다른|딴)(거|걸|것|걸로|거로|옷|코디)(는|은|요|줘|없어|있어|보여줘|말해줘)*")) {
            return new String[] {"outfit", "다른 거 추천해 줘"};
        }
        // 추천 뒤 "좀 더 캐주얼하게", "더 단정하게": 같은 날·자리로 다시 추천(2026-11-23 시뮬에서 GPT로 갔음).
        if (intent == Intent.OTHER && "outfit".equals(session.lastTopic)
                && s.matches("(그럼|그러면)?(좀|조금)?(더)?(캐주얼|편하|편한|가볍|가벼운|따뜻|시원|두껍|두꺼운|단정|깔끔|포멀|격식|차려입|꾸며|힙하|귀엽|귀여운|세련)"
                        + "[가-힣]{0,8}[?？!.~]*")
                && !s.contains("않") && !s.contains("같") && s.length() <= 16) {
            return new String[] {"outfit", "다른 거 추천해 줘"};
        }
        // 일정 뒤 "그날 뭐 입지?"도(2026-11-23 시뮬: "모레는 일정이 없어요" 뒤 오늘로 추천).
        if (intent == Intent.OUTFIT && day == null
                && ("weather".equals(session.lastTopic) || "schedule".equals(session.lastTopic))
                && session.lastTopicDate != null
                && (s.startsWith("그럼") || s.startsWith("그러면") || s.startsWith("그날"))) {
            var d = java.time.LocalDate.parse(session.lastTopicDate);
            return new String[] {"outfit", d.getMonthValue() + "월 " + d.getDayOfMonth() + "일 뭐 입지"};
        }
        return null;
    }

    /** 지난 턴에 카메라(옷)를 켜 둔 상태였으면 그 카메라, 아니면 null. */
    static Next outfitCamera(AgentSession session) {
        return session.lastNext == Next.camera_outfit || session.lastNext == Next.camera_worn ? session.lastNext : null;
    }

    private TurnResponse turn(AgentSession session, TurnRequest req, boolean inOutfit) {
        // 이번 대화에서 이미 답했으면 그 답을 쓰고, 아니면 앱이 기억한 답(없으면 null = 아직 안 물음).
        if (req.allowGpt() != null || !session.gptDecided) {
            session.gptConsent = req.allowGpt();
        }
        if (req.colorDetail() != null) {
            session.colorDetail = req.colorDetail();
        }

        if (req.hasImage()) {
            session.checkOffered = false;
            if ("face".equals(req.imageKind())) {
                return face.checkMakeup(session, req.image(), req.faceLandmarks(), req.faceFrames());
            }
            if ("teeth".equals(req.imageKind())) {
                return face.checkTeeth(session, req.image(), req.mouthBox());
            }
            // "이거 무슨 색이야?"로 켠 카메라: 색만 읽어 줍니다.
            if (session.colorCheck && !"worn".equals(req.imageKind())) {
                return color.check(session, req.image());
            }
            if ("worn".equals(req.imageKind())) {
                session.outfitMode = OutfitFlow.WORN;
                return outfit.checkWorn(session, req.image());
            }
            return outfit.check(session, req.image());
        }

        // "색 설명 짧게 해 줘", "색 설명 자세히 해 줘" (2026-11-13)
        Boolean detail = com.fashionagent.agent.judge.ColorFeel.detailCommand(req.utterance());
        if (detail != null) {
            session.colorDetail = detail;
            sessions.save(session);
            return new TurnResponse(session.sessionId,
                    detail ? com.fashionagent.agent.judge.ColorFeel.SAY_DETAILED
                            : com.fashionagent.agent.judge.ColorFeel.SAY_SHORT,
                    Next.listen, Result.of("color_detail"), null, detail);
        }

        // 색 확인 중 "됐어"면 끝내고, 다른 말이면 색 확인을 접고 원래대로 고릅니다.
        if (session.colorCheck) {
            TurnResponse answered = color.answer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // 일정을 넣은 뒤 "일정 전에 알림을 드릴까요?", "며칠 전에 미리 알려 드릴까요?"에 대한 답
        if (session.reminderStep != null) {
            TurnResponse answered = reminder.answer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "옷장에서 ○○를 지울까요?", "몇째 옷을 지울까요?"에 대한 답
        if (session.closetRemoveIds != null) {
            TurnResponse answered = closetFlow.answerRemove(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "애매한 부분을 더 정확한 AI에게 물어봐도 될까요?"에 대한 답
        if (session.gptAsked) {
            TurnResponse answered = outfit.answerGptAsk(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "어떻게 보여 주실래요?"에 대한 답
        if (session.modeAsked > 0) {
            TurnResponse chosen = outfit.answerMode(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }
        // 앱이 기억한 outfitMode는 쓰지 않습니다. 매번 고릅니다(2026-11-06 진행 님 결정).

        // 말로 추천한 뒤 "봐 드릴까요?"에 대한 답
        if (session.checkOffered) {
            TurnResponse answered = outfit.answerCheckOffer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "다 보여 줬어" 뒤 "~로 할까요?"에 대한 답
        if (session.pickAsked) {
            TurnResponse answered = outfit.answerPick(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "입은 모습도 확인해 보실래요?"에 대한 답
        if (session.wornOfferAsked) {
            TurnResponse answered = outfit.answerWornOffer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "얼굴을 볼까요, 이를 확인할까요?"에 대한 답
        if (session.faceChoiceAsked > 0) {
            TurnResponse chosen = face.answerChoice(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }

        // 추천을 듣고 날씨를 물은 뒤 "봐 줘": 2026-11-23 시뮬에서 "그건 할 수 없는 일이에요"로 답했음.
        // 추천을 들은 대화이고 카메라를 아직 안 켰으면 "봐 드릴까요?"에 답한 것처럼 이어 갑니다.
        if (inOutfit && session.advice != null && session.seen.isEmpty() && outfitCamera(session) == null
                && isLookRequest(req.utterance())) {
            TurnResponse answered = outfit.answerCheckOffer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // 카메라를 켜 둔 채 "응", "알겠어", "뒤도 볼게": 2026-11-23 시뮬에서 "할 수 없는 일이에요"로 답하고 카메라를 껐음.
        // 위의 네/아니요 질문들이 먼저 답을 가져가고, 남은 짧은 대답만 여기서 카메라를 이어 갑니다.
        // 카메라를 켠 채 "취소", "그만할래": 2026-11-23 시뮬에서 "할 수 없는 일이에요"로 답했음. 카메라를 끄고 멈춥니다.
        if (cameraCancel(session, req.utterance())) {
            return say(session, "네, 알겠어요. 필요하면 다시 불러 주세요.", Next.listen);
        }
        String waiting = cameraWaitSpeech(session, req.utterance());
        if (waiting != null) {
            return say(session, waiting, session.lastNext);
        }

        // "내일 날씨 어때?" 뒤 "모레는?", "토요일은?": 날짜만 말하면 방금 물은 것(날씨·옷·일정)을 그날로 다시 답합니다.
        // "그럼 뭐 입지?"는 방금 물은 날로 추천합니다. 2026-11-23 시뮬: "할 수 없는 일이에요", 오늘 추천으로 답했음.
        Intent intent = Intent.of(req.utterance(), inOutfit);
        String[] follow = followUp(session, req.utterance(), intent);
        if (follow != null) {
            String said = follow[1];
            return switch (follow[0]) {
                case "weather" -> remember(session, "weather", said, weather(session, req.location(), said));
                case "schedule" -> remember(session, "schedule", said, schedule(session, said));
                default -> remember(session, "outfit", said, outfit.start(session, req.location(), said));
            };
        }

        return switch (intent) {
            case OUTFIT -> remember(session, "outfit", req.utterance(),
                    outfit.start(session, req.location(), req.utterance()));
            case CHECK -> outfit.startCheck(session, req.location(), req.utterance());
            case COLOR -> color.start(session);
            case MODE -> outfit.switchMode(session, OutfitFlow.switchFrom(req.utterance()));
            case FINISH -> outfit.finish(session);
            case SHOWN -> outfit.shown(session);
            case WHERE -> outfit.where(session);
            case CLOSET -> closetFlow.list(session, req.utterance());
            case CLOSET_HISTORY -> closetFlow.history(session, req.utterance());
            case CLOSET_REMOVE -> closetFlow.startRemove(session, req.utterance());
            case WEATHER -> remember(session, "weather", req.utterance(), weather(session, req.location(), req.utterance()));
            case SCHEDULE -> remember(session, "schedule", req.utterance(), schedule(session, req.utterance()));
            case FACE -> face.start(session, req.utterance());
            // "할 줄 아는 거 말해 봐": GPT 여부와 상관없이 매번 같은 안내
            case HELP -> say(session, SmallTalk.CAPABILITIES, Next.listen);
            // 그 밖(일정 등록·삭제, 다른 날 일정 등)은 GPT가 도구를 고릅니다(약속 5-2절).
            case OTHER -> {
                // 카메라를 켜 둔 채 일정을 넣었을 때만 카메라를 이어 갑니다. 추천만 듣고 카메라를 안 켰으면 켜지 않습니다.
                Next camera = outfitCamera(session);
                Assistant.Reply r = assistant.reply(session, req.utterance(), req.location(), camera != null);
                // GPT가 말뜻을 보고 우리 기능을 고른 경우(키워드에 안 걸린 말). 그 흐름을 그대로 시작합니다.
                if (r.feature() != null) {
                    yield switch (r.feature()) {
                        case "outfit" -> outfit.start(session, req.location(), req.utterance());
                        case "face" -> face.start(session, "얼굴 확인");
                        case "teeth" -> face.start(session, "이 확인");
                        case "schedule_today" -> schedule(session, null);
                        default -> say(session, r.speech(), r.next());
                    };
                }
                // 방금 일정을 넣었으면 답 뒤에 "일정 전에 알림을 드릴까요?"를 붙이고 답을 기다립니다.
                if (ReminderFlow.PENDING.equals(session.reminderStep)) {
                    yield say(session, reminder.askIfAdded(session, r.speech()), Next.listen);
                }
                yield say(session, r.speech(), r.next() == Next.camera_outfit && camera != null ? camera : r.next());
            }
        };
    }

    /**
     * "오늘 날씨 어때?", "내일 날씨 어때?", "우산 챙겨야 해?" 판단 없이 날씨 값을 읽어 줍니다.
     * 다른 날을 물으면 weather_forecast로 그날 예보를 읽습니다. 2026-11-12 실제 서버: "내일 날씨"에 오늘 날씨로 답했습니다.
     */
    private TurnResponse weather(AgentSession session, Location location, String utterance) {
        Location at = location == null ? Location.SEOUL : location;
        // "부산 날씨 어때?": 말한 도시 날씨로 답하고, 어느 도시인지 먼저 말합니다.
        Cities.City city = Cities.in(utterance);
        if (city != null) {
            at = city.at();
        }
        session.lastCity = city == null ? null : city.name();
        var today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        if (SpokenDate.impossible(utterance, today)) {
            return say(session, SpokenDate.SAY_IMPOSSIBLE, Next.listen);
        }
        // "어제 날씨 어땠어?": 오늘 날씨로 답했음(2026-11-23 시뮬). 지난 날씨는 받을 수 없다고 말합니다.
        if (pastLabel(utterance) != null && SpokenDate.from(utterance, today) == null) {
            return say(session, "지난 날씨는 알 수 없어요. 오늘부터 16일 안쪽 날씨만 알려 드릴 수 있어요.", Next.listen);
        }
        var asked = SpokenDate.from(utterance, today);
        boolean otherDay = asked != null && !asked.equals(today);
        String label = SpokenDate.labelAsAsked(utterance, asked, today);
        try {
            JsonNode w = otherDay
                    ? tools.call(ToolNames.WEATHER_FORECAST, Map.of("lat", at.lat(), "lon", at.lon(), "date", asked.toString()))
                    : tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", at.lat(), "lon", at.lon()));
            String speech = weatherSpeech(w, otherDay ? label : null, utterance);
            // "내일 결혼식 가는데 원피스 입어도 돼?"처럼 같은 말에 자리가 있으면 그 자리로, 다음 "흰 원피스는?"에도 이어 씁니다.
            SpokenOccasion.Spoken said = SpokenOccasion.from(utterance);
            if (said != null) {
                session.spokenOccasion = said.occasion().name();
                session.spokenTitle = said.title();
            }
            String manner = occasionColorAdvice(session.spokenOccasion, utterance);
            if (manner != null) {
                speech = manner + " " + speech.replaceFirst("^네, (입어|신어)도 돼요\\. (다만 )?", "");
            }
            if (city != null) {
                speech = city.name() + " 날씨예요. " + speech;
            }
            sessions.save(session);
            return new TurnResponse(session.sessionId, speech, Next.listen, Result.of("weather"));
        } catch (ToolException e) {
            if ("out_of_range".equals(e.code())) {
                return say(session, label + " 날씨는 아직 알 수 없어요. 16일 안쪽 날씨만 알려 드릴 수 있어요.", Next.listen);
            }
            return say(session, "미안해요, 지금은 날씨를 못 가져왔어요. 조금 있다가 다시 물어봐 주실래요?", Next.listen);
        }
    }

    /** day가 null이면 지금 날씨, 아니면 "내일은 비, 최저 3도 최고 13도예요" 꼴. 우산을 물었으면 우산 답을 먼저. */
    static String weatherSpeech(JsonNode w, String day, String utterance) {
        String condition = w.path("condition").asString("");
        long min = Math.round(w.path("minTemperature").asDouble());
        long max = Math.round(w.path("maxTemperature").asDouble());
        int rain = w.path("precipitationProbability").asInt(0);
        boolean umbrella = w.path("needsUmbrella").asBoolean(false);
        String u = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        boolean askedRain = u.contains("우산") || u.contains("비") || u.contains("장화") || u.contains("우비")
                || u.replaceAll("[?？.!~]", "").endsWith("눈")
                || u.contains("레인") || u.contains("눈와") || u.contains("눈올")
                || u.contains("눈오") || u.contains("눈이");

        StringBuilder sb = new StringBuilder();
        String feel = feeling(u, w.path("apparentTemperature").asDouble(), day);
        if (feel != null) {
            sb.append(feel).append(" ");
        }
        // "패딩 입어야 돼?", "겉옷 챙겨야 해?": 날씨를 읽기 전에 네/아니요부터(2026-11-23 시뮬).
        if (!askedRain && (u.contains("입어야") || u.contains("챙겨야") || u.contains("필요")
                || u.contains("없지") || u.contains("안입어도") || u.contains("안챙겨도"))
                && (u.contains("겉옷") || u.contains("외투") || u.contains("패딩") || u.contains("코트")
                        || u.contains("자켓") || u.contains("재킷") || u.contains("점퍼") || u.contains("입어야"))
                && w.has("needsOuter")) {
            boolean neg = u.matches(".*(없지|없겠지|필요없|안입어도|안챙겨도).*");
            boolean need = w.path("needsOuter").asBoolean();
            sb.append(need ? (neg ? "아니요, 겉옷을 챙기세요. " : "네, 겉옷을 챙기세요. ")
                    : (neg ? "네, 겉옷은 없어도 돼요. " : "아니요, 겉옷은 없어도 돼요. "));
        }
        // "반팔 입어도 돼?": 겉옷이 필요하면 아니요, 최고 24도 이상이면 네(2026-11-23 시뮬).
        if (!askedRain && Intent.lightMay(u)) {
            if (w.path("needsOuter").asBoolean(false)) {
                sb.append("아니요, 그렇게 입기엔 추워요. 겉옷을 챙기세요. ");
            } else if (max >= 24) {
                sb.append("네, 그렇게 입어도 돼요. ");
            } else {
                sb.append("조금 쌀쌀할 수 있어요. 얇은 겉옷을 하나 챙기세요. ");
            }
        }
        // "치마 입어도 돼?", "청바지 괜찮아?": 옷 자체는 괜찮고, 추위만 덧붙입니다(2026-11-23 시뮬).
        if (!askedRain && Intent.wearMay(u)) {
            boolean skirt = u.contains("치마") || u.contains("스커트") || u.contains("원피스");
            boolean outer = "outer".equals(ClosetView.category(u));
            String wear = "shoes".equals(ClosetView.category(u)) ? "신어도" : "입어도";
            boolean cold = w.path("needsOuter").asBoolean(false);
            if (skirt && min <= 5) {
                sb.append("네, " + wear + " 돼요. 다만 최저 ").append(min).append("도라 두꺼운 스타킹을 신고 겉옷을 챙기세요. ");
            } else if (outer && !cold && max >= 22) {
                sb.append(wear + " 되지만 최고 ").append(max).append("도라 낮에는 더울 수 있어요. ");
            } else if (cold && !outer) {
                sb.append("네, " + wear + " 돼요. 다만 추우니 겉옷을 챙기세요. ");
            } else {
                sb.append("네, " + wear + " 돼요. ");
            }
        }
        // "목도리 해야 돼?", "장갑 챙길까?": 지금은 체감, 다른 날은 최저 기온으로(2026-11-23 시뮬).
        if (!askedRain && Intent.WARM_ITEMS.stream().anyMatch(u::contains)
                && (Intent.NEED_WORDS.stream().anyMatch(u::contains) || u.replaceAll("\\d{1,2}월\\d{1,2}일|[?.!~]", "").length() <= 5)) {
            // 오늘은 지금 체감과 최저 중 더 추운 쪽(아래 옷차림 말 "목도리처럼 따뜻한 옷"이 최저 기준이라 맞춥니다).
            long feels = Math.round(w.path("apparentTemperature").asDouble());
            boolean byFeel = day == null && feels <= min;
            long cold = byFeel ? feels : min;
            String basis = (byFeel ? "체감 " : "최저 ") + cold + "도";
            // "목도리 필요 없지?"처럼 거꾸로 물으면 네/아니요도 거꾸로(2026-11-23 시뮬: "네, 챙기세요"가 헷갈림).
            boolean negative = u.matches(".*(없지|없겠지|안해도|안챙겨도|안껴도|필요없|안해도돼).*");
            if (cold <= 3) {
                sb.append(negative ? "아니요, 챙기세요. " : "네, 챙기세요. ").append(basis).append("라 추워요. ");
            } else if (cold <= 8) {
                sb.append("있으면 좋아요. ").append(basis).append("라 조금 쌀쌀해요. ");
            } else {
                sb.append(negative ? "네, 없어도 돼요. " : "아니요, 없어도 돼요. ").append(basis).append("예요. ");
            }
        }
        // 오늘 비 확률은 하루 중 가장 높은 값입니다. 지금 하늘이 맑으면 "맑음, 비 100%"가 헷갈려서(2026-11-23 젯슨 확인)
        // "지금은 안 오지만 오늘 중에"라고 말합니다.
        boolean laterToday = day == null && !(condition.contains("비") || condition.contains("눈")
                || condition.contains("소나기") || condition.contains("뇌우"));
        if (askedRain) {
            // "우산 안 챙겨도 되지?"처럼 거꾸로 물으면 네/아니요를 먼저(2026-11-23 시뮬).
            if (u.matches(".*(안챙겨도|안가져가도|필요없|없어도|안써도).*")) {
                sb.append(umbrella ? "아니요, " : "네, ");
            } else if (u.matches(".*(장화|우비|레인).*")) {
                // "장화 신을까?": 우산 답만 하면 장화를 신으라는지 몰랐음.
                sb.append(umbrella ? "네, 비가 와서 신는 게 좋아요. " : "아니요, 필요 없어요. ");
            }
            sb.append(umbrella
                    ? "우산 챙기세요. " + (laterToday ? "지금은 안 오지만 오늘 중에 " : "") + "비나 눈 올 확률이 " + rain + "%예요. "
                    : "우산은 안 챙기셔도 돼요. 비 올 확률이 " + rain + "%예요. ");
        }
        if (day == null) {
            sb.append("지금 ").append(condition).append(", ")
                    .append(Math.round(w.path("temperature").asDouble())).append("도이고 체감 ")
                    .append(Math.round(w.path("apparentTemperature").asDouble())).append("도예요. ");
        } else {
            sb.append(Korean.josa(day, "은", "는")).append(" ").append(condition).append(", ");
        }
        sb.append("최저 ").append(min).append("도, 최고 ").append(max).append("도예요. ");
        if (!askedRain && umbrella) {
            sb.append(laterToday ? "지금은 안 오지만 오늘 중에 " : "").append("비 올 확률이 ").append(rain)
                    .append("%라 우산을 챙기세요. ");
        }
        sb.append(w.path("clothingAdvice").asString(""));
        return sb.toString().trim();
    }

    /**
     * "추워?", "더워?"에는 체감 온도로 먼저 한마디 답합니다. 2026-11-12 실제 서버: 날씨만 읽어 줘서 답이 안 됐습니다.
     * 기준은 mcp-weather의 옷 조언과 비슷하게 잡았습니다(체감 5도 이하 추움, 12도 이하 쌀쌀, 28도 이상 더움).
     */
    static String feeling(String u, double apparent, String day) {
        boolean cold = u.contains("추워") || u.contains("춥") || u.contains("추운") || u.contains("쌀쌀");
        boolean hot = u.contains("더워") || u.contains("덥") || u.contains("더운");
        if (cold == hot) {
            return null;
        }
        String when = day == null ? "지금" : Korean.josa(day, "은", "는");
        // 오늘은 뒤 문장에 "체감 n도"가 또 나오니 여기서는 빼요(2026-11-12 실제 서버: 두 번 들림).
        String t = day == null ? "" : " 체감 " + Math.round(apparent) + "도예요.";
        if (cold) {
            if (apparent <= 5) {
                return "네, " + when + " 추워요." + t;
            }
            if (apparent <= 12) {
                return "조금 쌀쌀해요." + t;
            }
            if (apparent < 20) {
                return "춥지는 않고 선선해요." + t;
            }
            return "아니요, 춥지 않아요." + t;
        }
        if (apparent >= 28) {
            return "네, " + when + " 더워요." + t;
        }
        if (apparent >= 23) {
            return "조금 더워요." + t;
        }
        return "아니요, 덥지 않아요." + t;
    }

    /** "오늘 일정 뭐야?", "11월 20일 일정 뭐야?" */
    private TurnResponse schedule(AgentSession session, String utterance) {
        var today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        if (SpokenDate.impossible(utterance, today)) {
            return say(session, SpokenDate.SAY_IMPOSSIBLE, Next.listen);
        }
        var asked = SpokenDate.from(utterance, today);
        // "어제 일정 뭐였지?": 지난 날은 SpokenDate가 안 읽어 "오늘은 일정이 없어요"로 답했음(2026-11-23 시뮬).
        String past = pastLabel(utterance);
        if (asked == null && past != null) {
            String u = utterance.replaceAll("\\s+", "");
            asked = switch (past) {
                case "어제" -> today.minusDays(1);
                case "그제" -> today.minusDays(2);
                default -> ClosetFlow.pastDay(u, today);
            };
        }
        var day = asked == null ? today : asked;
        String label = day.equals(today.minusDays(1)) ? "어제" : day.equals(today.minusDays(2)) ? "그제"
                : SpokenDate.label(day, today);
        try {
            JsonNode r = tools.callForUser(ToolNames.SCHEDULE_LIST, session.userId, Map.of("date", day.toString()));
            JsonNode events = r.path("events");
            if (events.isEmpty()) {
                return say(session, Korean.josa(label, "은", "는") + " 일정이 없어요.", Next.listen);
            }
            StringBuilder sb = new StringBuilder(label + " 일정은 ");
            for (int i = 0; i < events.size(); i++) {
                JsonNode e = events.get(i);
                String start = e.path("start").asString("");
                String time = spokenTime(start);
                sb.append(i == 0 ? "" : ", ").append(time).append(" ").append(e.path("title").asString(""));
            }
            sb.append("이에요.");
            sessions.save(session);
            return new TurnResponse(session.sessionId, sb.toString(), Next.listen, Result.of("schedule"));
        } catch (ToolException e) {
            return say(session, "미안해요, 지금은 일정을 못 가져왔어요. 조금 있다가 다시 물어봐 주실래요?", Next.listen);
        }
    }

    /**
     * 결혼식 추천 뒤 "흰 셔츠 입어도 돼?": 날씨만 보고 "네"라고 했음(2026-11-23 시뮬, 추천은 "흰색 상의를 피하세요").
     * 말한 자리에 맞지 않는 색이면 그 까닭을 먼저 말합니다.
     */
    static String occasionColorAdvice(String occasion, String utterance) {
        String u = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (occasion == null || !Intent.wearMay(u)) {
            return null;
        }
        boolean white = u.matches(".*(흰|하얀|화이트|흰색|아이보리).*");
        boolean bright = u.matches(".*(빨간|빨강|주황|노란|노랑|분홍|핑크|형광|하늘색|민트|화려).*");
        String kind = ClosetView.category(u);
        if ("wedding".equals(occasion) && white && !"bottom".equals(kind) && !"shoes".equals(kind)) {
            return "결혼식에는 흰색 옷은 신부와 겹쳐서 피하는 게 좋아요. 다른 색으로 입으세요.";
        }
        if ("mourning".equals(occasion) && (bright || white)) {
            return "조문 자리에는 검정이나 어두운 색이 좋아요. 밝은 색은 피하세요.";
        }
        return null;
    }

    /** 지난 날을 말했으면 "어제"/"그제"/"지난", 아니면 null. */
    static String pastLabel(String utterance) {
        String u = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (u.contains("어제")) {
            return "어제";
        }
        if (u.contains("그제") || u.contains("그저께")) {
            return "그제";
        }
        return u.matches(".*(지난|저번)(주)?[월화수목금토일]요일.*") ? "지난" : null;
    }

    /** "2026-11-04T14:00:00+09:00" → "오후 2시", 분이 있으면 "오후 2시 30분". */
    static String spokenTime(String iso) {
        try {
            var t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"));
            int h = t.getHour();
            String ampm = h < 12 ? "오전" : "오후";
            int h12 = h % 12 == 0 ? 12 : h % 12;
            return ampm + " " + h12 + "시" + (t.getMinute() == 0 ? "" : " " + t.getMinute() + "분");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private TurnResponse say(AgentSession session, String speech, Next next) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, null);
    }
}
