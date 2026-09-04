package com.pulseops.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claude 연동 설정이다.
 * <p>
 * 기본은 꺼져 있다. AI 분석은 이 Application이 유일하게 외부에 요금이 발생하는 요청을 보내는 자리이고,
 * 나머지 기능은 그것 없이도 전부 동작한다. 켜져 있는 것이 기본이면 credential이 없는 환경에서 기동이나
 * 테스트가 실패하거나, 더 나쁘게는 아무도 의도하지 않은 요청이 나간다.
 * <p>
 * apiKey는 환경 변수에서만 온다. 이 record에는 값을 담을 자리만 있고 기본값이 없으며,
 * {@code application.properties}에도 실제 값이 아니라 환경 변수 참조만 적는다. 설정 파일에 적힌 key는
 * 그 순간 repository에 들어갈 수 있는 값이 되고, 한 번 들어가면 history에서 지울 수 없다.
 * <p>
 * model 이름을 코드에 박지 않는다. 쓸 수 있는 model은 시간이 지나면 바뀌고, 그때 코드를 고쳐 다시
 * 배포해야 한다면 그것은 설정이어야 할 값이 코드에 있는 것이다.
 * <p>
 * base URL과 timeout은 설정으로 열지 않는다. 공식 endpoint는 하나이고 timeout은 이 Application이 감당할
 * 수 있는 대기 시간이라 운영자가 요청마다 다르게 정할 값이 아니다. 테스트는 이 값들을 바꾸지 않고
 * HTTP client 자리를 바꿔 끼우므로, 테스트 편의를 위해 설정을 늘릴 이유도 없다.
 */
@ConfigurationProperties(prefix = "pulseops.ai.claude")
public record ClaudeAnalysisProperties(boolean enabled, String apiKey, String model) {

}
