package com.fashionagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 게이트웨이가 그대로 넘긴 로그인 토큰을 agent가 직접 확인합니다(X-User-Id 헤더 대신, 2026-11-23). */
class AccessTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-23T06:00:00Z");
    private final AccessTokens tokens = new AccessTokens(TestTokens.SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
    private final String user = UUID.randomUUID().toString();

    private static String codeOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(401);
            return e.code();
        }
    }

    @Test
    @DisplayName("Given 같은 비밀키로 만든 토큰 When 확인하면 Then sub의 사용자 id")
    void validToken() {
        String bearer = "Bearer " + TestTokens.token(user, TestTokens.SECRET, NOW.plusSeconds(3600));

        assertThat(tokens.userId(bearer)).isEqualTo(user);
        assertThat(tokens.userId("bearer " + bearer.substring(7))).isEqualTo(user);
    }

    @Test
    @DisplayName("Given 토큰이 없거나 Bearer가 아님 When 확인하면 Then 401 unauthorized")
    void missing() {
        assertThat(codeOf(() -> tokens.userId(null))).isEqualTo("unauthorized");
        assertThat(codeOf(() -> tokens.userId(""))).isEqualTo("unauthorized");
        assertThat(codeOf(() -> tokens.userId("Basic abc"))).isEqualTo("unauthorized");
        assertThat(codeOf(() -> tokens.userId("Bearer not-a-jwt"))).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("Given 다른 비밀키로 만든 토큰(위조) When 확인하면 Then 401 unauthorized")
    void wrongSecret() {
        String forged = TestTokens.token(user, "another-secret-0123456789abcdef012345", NOW.plusSeconds(3600));

        assertThat(codeOf(() -> tokens.userId("Bearer " + forged))).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("Given 만료된 지 1분 지난 토큰 When 확인하면 Then 401 token_expired, 시계 차이(30초) 안이면 받는다")
    void expired() {
        String old = TestTokens.token(user, TestTokens.SECRET, NOW.minusSeconds(60));
        String justExpired = TestTokens.token(user, TestTokens.SECRET, NOW.minusSeconds(10));

        assertThat(codeOf(() -> tokens.userId("Bearer " + old))).isEqualTo("token_expired");
        assertThat(tokens.userId("Bearer " + justExpired)).isEqualTo(user);
    }

    @Test
    @DisplayName("Given sub가 UUID가 아님 When 확인하면 Then 401 unauthorized")
    void subjectNotUuid() {
        String bad = TestTokens.token("u1", TestTokens.SECRET, NOW.plusSeconds(3600));

        assertThat(codeOf(() -> tokens.userId("Bearer " + bad))).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("Given 서버에 JWT_SECRET이 없거나 짧음 When 확인하면 Then 어떤 토큰도 받지 않는다(401)")
    void noSecret() {
        AccessTokens none = new AccessTokens("", Clock.fixed(NOW, ZoneOffset.UTC));
        AccessTokens shortOne = new AccessTokens("short", Clock.fixed(NOW, ZoneOffset.UTC));
        String bearer = "Bearer " + TestTokens.token(user, TestTokens.SECRET, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> none.userId(bearer)).isInstanceOf(ApiException.class);
        assertThat(codeOf(() -> shortOne.userId(bearer))).isEqualTo("unauthorized");
    }
}
