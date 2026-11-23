package com.fashionagent.agent.api;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;


/**
 * 로그인 토큰(게이트웨이가 만든 JWT, HS256)을 직접 확인해 사용자 id(sub)를 꺼냅니다.
 *
 * <p>2026-11-23 정지원 결정: 사용자 id를 X-User-Id 헤더로 넘기지 않고, 게이트웨이가 받은 {@code Authorization: Bearer}
 * 토큰을 그대로 넘기면 각 서비스가 확인합니다. 비밀키는 게이트웨이와 같은 JWT_SECRET입니다.
 */
@Component
public class AccessTokens {

    private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);
    /** 게이트웨이가 확인한 직후 넘기므로 시계 차이·전달 시간만 봐 줍니다. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final byte[] secret;
    private final Clock clock;

    public AccessTokens(@Value("${jwt-secret:}") String secret, Clock clock) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            log.error("JWT_SECRET이 없거나 32바이트보다 짧아요. 게이트웨이와 같은 값을 넣어야 로그인한 요청을 받을 수 있어요.");
            bytes = null;
        }
        this.secret = bytes;
        this.clock = clock;
    }

    /** "Bearer 토큰" → 사용자 id. 없거나 틀리면 401 unauthorized, 만료면 401 token_expired. */
    public String userId(String authorization) {
        if (secret == null) {
            throw error("unauthorized", "서버에 JWT_SECRET이 설정되지 않았어요.");
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw error("unauthorized", "로그인 토큰이 없어요.");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7).strip());
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                throw error("unauthorized", "토큰이 틀렸어요.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null) {
                throw error("unauthorized", "토큰이 틀렸어요.");
            }
            if (!exp.toInstant().plus(CLOCK_SKEW).isAfter(clock.instant())) {
                throw error("token_expired", "토큰이 만료됐어요.");
            }
            return UUID.fromString(claims.getSubject()).toString();
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException e) {
            throw error("unauthorized", "토큰이 틀렸어요.");
        }
    }

    private static ApiException error(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
