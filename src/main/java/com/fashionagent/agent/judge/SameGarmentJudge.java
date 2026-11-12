package com.fashionagent.agent.judge;

/**
 * 옷장 서버가 "애매하게 비슷하다"(closet_save maybeSameAs)고 한 두 옷이 같은 옷인지 사진 두 장으로 확인합니다.
 * 2026-11-12 박창근 님 결정: 모델이 확실할 때는 모델만, 애매하거나 인식이 안 될 때만 GPT로 한 번 더 봅니다.
 */
public interface SameGarmentJudge {

    /** 이번 옷 사진과 옷장 옷 사진(둘 다 옷 부분만 자른 base64 JPEG)이 같은 옷이면 true. 모르면 false(합치지 않음). */
    boolean same(String nowJpegBase64, String closetJpegBase64, String closetDescription);

    SameGarmentJudge NONE = (a, b, d) -> false;
}
