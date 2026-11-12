# fashion-agent-agent

**agent · 데이터를 모아 GPT로 판단하는 두뇌** · 패션 에이전트(시각장애인용 음성 AI 옷 추천)의 한 부분입니다.

담당: 이진행

## 한눈에

- 포트: 8081, 엔드포인트: `http://localhost:8081`
- 기술: Spring Boot, Spring AI (ChatClient + MCP 클라이언트), GPT(OpenAI, 기본 `gpt-5.4-mini`), Redis(대화 상태)
- 상태 확인: `GET /actuator/health`

## API

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/agent/turn` | 대화 한 번. 말 또는 앱이 자동 전송한 사진 한 장을 받아 `speech`, `next`, `result`를 돌려줌 |

입출력 JSON은 [OpenAPI](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)를 그대로 따릅니다.

## 이 레포가 지킬 것

- **중심 기능은 옷 추천 2단계**입니다([흐름]({C}#5-기능-구분과-agent-처리-방법)).
  1. 말로 추천: `weather_today` + `schedule_list` + (선택) `closet_search`를 동시에 부르고 GPT(추천 모드)로 추천 → `next: camera_outfit`
  2. 카메라로 확인: 앱이 자동 전송한 한 장마다 `clothes_inspect` → GPT(확인 모드)로 한 벌 판단 또는 두 벌 비교 → "이걸로 할게"면 `closet_save` + `closet_wear` → `next: done`
- 옷 추천은 **정해진 순서(코드)로** 도구를 부릅니다. GPT가 도구를 고르는 건 개별 기능(날씨 묻기, 일정 등록, 얼굴 확인, 가족에게 묻기)뿐입니다.
- MCP 서버는 데이터만 줍니다. 판단은 GPT에 판단 기준(약속 문서 5-1절)을 주고 구조화 출력으로 받습니다.
- 사진 base64는 GPT 대화에 넣지 않고 도구에 직접 넘깁니다.
- 도구의 `userId`는 GPT 값이 아니라 `X-User-Id`로 덮어씁니다.
- 얼룩은 위치보다 할 일(빨기·수선·바꾸기)을 먼저 말하고, 위치는 사용자가 물을 때만.
- 대화 상태(1단계 추천, 본 옷, 직전 사진 10분)는 Redis에 30분. DB는 쓰지 않습니다.

## 실행하기

Java 21만 있으면 됩니다. 기본 설정은 **서버 없이 혼자 실행**(가짜 도구 + 규칙 판단 + 메모리 세션)이라 다른 레포가 없어도 흐름 전체를 돌려 볼 수 있습니다.

```bash
./gradlew bootRun                     # http://localhost:8081
./gradlew test                        # 옷 추천 2단계 전체 + 진짜 MCP 서버 연결 테스트
```

```bash
# 1단계: 말로 추천 → sessionId를 받아 둠
curl -s localhost:8081/api/v1/agent/turn -H 'X-User-Id: u1' -H 'Content-Type: application/json' \
  -d '{"utterance":"오늘 뭐 입지?"}'
# 2단계: 앱이 자동 전송한 사진 한 장 (가짜 도구는 내용과 상관없이 "왼쪽 흰 셔츠(얼룩) + 오른쪽 체크 셔츠")
curl -s localhost:8081/api/v1/agent/turn -H 'X-User-Id: u1' -H 'Content-Type: application/json' \
  -d '{"sessionId":"<받은 값>","image":"AAAA","imageKind":"outfit"}'
# "얼룩 어디야?" → 위치, "이걸로 할게" → 기록하고 next=done
```

| 프로필 | 도구 | 판단 | 세션 | 언제 |
| --- | --- | --- | --- | --- |
| (없음) | 가짜 | 규칙 | 메모리 | 혼자 개발 |
| `mcp` | 진짜 MCP 서버 (`MCP_*_URL`) | 규칙 | 메모리 | mcp-weather 등과 붙여 보기 |
| `mcp,openai` | 진짜 | GPT (`OPENAI_API_KEY`) | 메모리 | GPT 판단 확인 |
| `dev`, `prod` | 진짜 | GPT | Redis (`REDIS_HOST`) | Docker Compose |

`SPRING_PROFILES_ACTIVE=mcp ./gradlew bootRun`처럼 켭니다. 판단 모델은 `AGENT_OPENAI_MODEL`(기본 `gpt-5.4-mini`), 생각 정도는 `AGENT_OPENAI_REASONING`(기본 `none`)입니다. 2026-11-04 Gemini에서 GPT로 바꿨습니다: Gemini 무료 등급은 모델마다 하루 20번이라 도구를 쓰는 대화(한 번에 요청 2~3번) 몇 번이면 막혔고, 팀에 OpenAI 크레딧이 있었습니다. 같은 날 실제 키로 모델을 비교했습니다: `gpt-5.4-mini`(none)는 대부분 1~4초, `gpt-5-mini`(low)는 옷 확인이 16~19초로 25초 제한에 빠듯했고, 기본 설정의 `gpt-5-mini`는 25초를 넘었습니다. 자세한 값은 `application-openai.yml` 주석에 있습니다.

키는 `.env`(커밋 안 됨)에 `OPENAI_API_KEY=...`로 두고 `set -a; . ./.env; set +a` 후 실행하면 편합니다. MCP 서버가 아직 안 켜져 있어도 agent는 뜨고, 부를 때 다시 붙습니다. 도구가 실패하면 그 데이터 없이 계속하고, GPT가 실패하거나 25초 안에 답하지 않으면 규칙으로 판단합니다(사용자는 항상 답을 들음). 사용량 초과(429)는 다시 보내지 않고 바로 규칙·안내 문장으로 넘어갑니다. 개별 기능에서 일정을 등록·삭제하거나 가족에게 보낸 뒤 GPT가 실패하면, 이미 한 일은 말해 줍니다.

## 코드 구조

```
api/       TurnController (POST /api/v1/agent/turn), 요청·응답, 오류 모양 {"error":{code,message}}
flow/      TurnService (어느 흐름으로 보낼지), Intent (키워드), OutfitFlow (옷 추천 2단계), FaceFlow (얼굴 확인)
judge/     OutfitJudge: LlmOutfitJudge (GPT 구조화 출력) / RuleOutfitJudge (규칙) / Fallback, 지시문 Prompts
tools/     AgentTools: McpAgentTools (Streamable HTTP) / FakeAgentTools, 도구 이름 ToolNames
assistant/ 개별 기능: LlmAssistant(GPT 도구 선택) / NoAssistant, GPT가 고르는 도구 AssistantTools
session/   AgentSession, SessionStore: 메모리 / Redis (30분, 키에 userId 포함)
```

## 할 일

- [x] Spring Boot 뼈대 + `/actuator/health` + Dockerfile
- [x] 개발 중엔 게이트웨이 없이 `X-User-Id` 헤더로 직접 호출
- [x] LLM 연결 (Spring AI), 구조화 출력으로 추천·확인 모드 (2026-11-04 Gemini 실제 키로 확인 → GPT로 바꿈)
- [x] MCP 클라이언트 (테스트 안에서 Spring AI MCP 서버에 붙여 확인), 가짜 도구로 1·2단계 완성
- [x] mcp-weather, mcp-schedule 나오면 `mcp` 프로필로 붙여 보기 (2026-11-04 진짜 Open-Meteo로 1단계 추천 확인)
- [ ] 2단계: mcp-clothes-state 준비 전에는 지금 배포된 Gemini 옷 분석 서버를 임시로 사용
- [x] Redis 세션, 두 벌 비교, "어디야?", 마무리(closet_save·closet_wear)
- [x] 개별 기능: GPT 도구 선택(`assistant/`: schedule_add·list·delete, weather_today). 날씨·오늘 일정·얼굴 확인은 키워드로 바로 처리
- [x] 얼굴 확인(`FaceFlow`): "얼굴 확인해 줘" → "얼굴 화장을 볼까요, 이를 확인할까요?" → `camera_face` 또는 `camera_teeth` → 사진 한 장 → `face_makeup_check` 또는 `teeth_check` 결과 문장을 이어 읽기 ("이 확인해 줘"처럼 처음부터 말하면 묻지 않음)
- [x] 실제 키로 일정 등록 날짜 해석("다음 주 토요일 두 시" → 11/14 14:00), 시간 빠지면 되묻기, 다른 날 조회 확인 (2026-11-04, Gemini)
- [ ] 실제 GPT 키로 다시 확인: 일정 등록·삭제, 되묻기, 가족에게 묻기, 25초 안에 답하는지
- [x] 가족에게 묻기용 직전 사진 10분 보관

## 공통 약속

모든 레포가 지키는 약속은 앱 레포에 있습니다. **코드보다 약속 문서를 먼저 고칩니다.**

- [서비스 간 약속](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/README.md): 공통 규칙, 포트, 인증(`X-User-Id`), 오류 모양, DB 스키마, 옷 추천 흐름
- [MCP 도구 입출력](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/mcp-tools.md)
- [앱 ↔ 게이트웨이 API (OpenAPI)](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)
- [아키텍처와 기술 스택](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/ARCHITECTURE.md)

## 버전

| 항목 | 버전 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.9 |
| Spring Cloud | 2025.1.4 |
| Spring AI (BOM) | 2.0.2 |

## 브랜치

GitFlow를 따릅니다. `develop`에서 `feature/…` 브랜치를 따서 PR로 합칩니다. 규칙은 앱 레포의 `docs/GITFLOW.md`를 봅니다.

## 비밀 값

API 키와 DB 비밀번호는 레포에 넣지 않습니다. 로컬은 `.env`(커밋 금지), 배포는 서버 환경 변수로 둡니다. 목록은 앱 레포 `docs/infra/.env.example`에 있습니다.
