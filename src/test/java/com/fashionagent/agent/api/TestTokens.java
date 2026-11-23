package com.fashionagent.agent.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** 테스트용 로그인 토큰. 게이트웨이(JwtService)와 같은 모양(HS256, sub=사용자 UUID, exp). */
public final class TestTokens {

    /** 테스트 설정 jwt-secret과 같은 값(32바이트 이상). */
    public static final String SECRET = "test-secret-0123456789abcdef0123456789";
    /** @SpringBootTest(properties = ...)에 넣는 값. */
    public static final String PROPERTY = "jwt-secret=" + SECRET;

    private TestTokens() {
    }

    /** 테스트에서 쓰는 이름("u1")마다 늘 같은 사용자 UUID. */
    public static String id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** "Bearer <토큰>". sub는 {@link #id(String)}. */
    public static String bearer(String name) {
        return "Bearer " + token(id(name), SECRET, Instant.now().plusSeconds(3600));
    }

    public static String token(String subject, String secret, Instant exp) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("provider", "dev")
                    .issueTime(new Date()).expirationTime(Date.from(exp)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
