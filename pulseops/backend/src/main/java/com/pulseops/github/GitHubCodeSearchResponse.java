package com.pulseops.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * GitHub Code Search API가 돌려주는 응답 중 이 기능이 쓰는 부분이다.
 * <p>
 * 모르는 field를 무시한다. 이 모양은 GitHub이 정하고 우리가 쓰지 않는 값이 훨씬 많이 오며, 그쪽이
 * field를 하나 더 붙였다는 이유로 맥락 수집이 실패하면 안 된다.
 * <p>
 * {@code path}만 쓴다. 응답에는 점수와 저장소 정보와 일치 구간까지 담겨 오지만, 우리가 파일을 고르는
 * 기준은 경로와 이름이고 내용은 뒤에서 Contents API로 다시 읽는다. 검색이 돌려준 조각을 그대로 쓰지
 * 않는 이유는 그것이 기본 branch의 지금 내용이며 Incident 시점의 것이 아니기 때문이다.
 * <p>
 * PulseOps의 REST DTO와 같은 Class를 쓰지 않는다. 이 모양은 우리가 정한 것이 아니라 외부 API가 정한
 * 것이고, 우리 API 응답과 한 Class를 공유하면 남의 계약 변경이 우리 Client에게 그대로 전달된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCodeSearchResponse(List<Item> items) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(String path) {
	}

}
