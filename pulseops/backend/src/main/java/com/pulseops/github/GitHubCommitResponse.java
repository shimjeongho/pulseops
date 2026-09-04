package com.pulseops.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * GitHub List Commits API가 돌려주는 commit 하나 중 이 기능이 쓰는 부분이다.
 * <p>
 * 작성자 정보를 읽지 않는다. 응답에는 이름과 email과 GitHub 계정이 함께 오지만, 이 기능은 누가
 * 바꿨는지를 묻지 않는다. 읽지 않으면 실수로 저장되거나 외부 model로 나갈 일도 없다.
 * <p>
 * 시각은 committer의 것을 읽는다. author의 시각은 patch를 처음 만든 때라 rebase나 cherry-pick 뒤에는
 * 실제로 그 branch에 들어온 시점보다 훨씬 이를 수 있고, Incident 이전인지 이후인지를 가리는 기준으로는
 * 그 branch에 반영된 시점이 맞다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommitResponse(String sha, Commit commit) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Commit(String message, Committer committer) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Committer(Instant date) {
	}

}
