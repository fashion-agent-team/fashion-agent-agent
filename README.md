# fashion-agent-agent

**agent · 데이터를 모아 Gemini로 판단하는 두뇌** · 패션 에이전트(시각장애인용 음성 AI 옷 추천)의 한 부분입니다.

담당: 이진행

## 한눈에

- 포트: 8081, 엔드포인트: `http://localhost:8081`
- 기술: Spring Boot, Spring AI (ChatClient + MCP 클라이언트), Gemini 3.5 Flash, Redis(대화 상태)
- 상태 확인: `GET /actuator/health`

## API

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/agent/turn` | 대화 한 번. 말 또는 앱이 자동 전송한 사진 한 장을 받아 `speech`, `next`, `result`를 돌려줌 |

입출력 JSON은 [OpenAPI](https://github.com/fashion-agent-team/fashion-agent/blob/develop/docs/contracts/openapi.yaml)를 그대로 따릅니다.

## 이 레포가 지킬 것

- **중심 기능은 옷 추천 2단계**입니다([흐름]({C}#5-기능-구분과-agent-처리-방법)).
  1. 말로 추천: `weather_today` + `schedule_list` + (선택) `closet_search`를 동시에 부르고 Gemini(추천 모드)로 추천 → `next: camera_outfit`
  2. 카메라로 확인: 앱이 자동 전송한 한 장마다 `clothes_inspect` → Gemini(확인 모드)로 한 벌 판단 또는 두 벌 비교 → "이걸로 할게"면 `closet_save` + `closet_wear` → `next: done`
- 옷 추천은 **정해진 순서(코드)로** 도구를 부릅니다. Gemini가 도구를 고르는 건 개별 기능(날씨 묻기, 일정 등록, 얼굴 확인, 가족에게 묻기)뿐입니다.
- MCP 서버는 데이터만 줍니다. 판단은 Gemini에 판단 기준(약속 문서 5-1절)을 주고 구조화 출력으로 받습니다(`temperature` 0.2).
- 사진 base64는 Gemini 대화에 넣지 않고 도구에 직접 넘깁니다.
- 도구의 `userId`는 Gemini 값이 아니라 `X-User-Id`로 덮어씁니다.
- 얼룩은 위치보다 할 일(빨기·수선·바꾸기)을 먼저 말하고, 위치는 사용자가 물을 때만.
- 대화 상태(1단계 추천, 본 옷, 직전 사진 10분)는 Redis에 30분. DB는 쓰지 않습니다.

## 할 일

- [ ] Spring Boot 뼈대 + `/actuator/health` + Dockerfile
- [ ] 개발 중엔 게이트웨이 없이 `X-User-Id` 헤더로 직접 호출
- [ ] Gemini 연결 (Spring AI), 구조화 출력으로 1단계 추천 모드
- [ ] mcp-weather 연결 (MCP 클라이언트) → 일정은 가짜 JSON으로 1단계 완성
- [ ] 2단계: mcp-clothes-state 준비 전에는 지금 배포된 Gemini 옷 분석 서버를 임시로 사용
- [ ] Redis 세션, 두 벌 비교, 마무리(closet_save·closet_wear)
- [ ] 개별 기능: Gemini 도구 선택(schedule_add, face_check, helper_*)

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
