package com.pulseops.github;

import com.pulseops.incident.Incident;
import java.time.Instant;

/**
 * 코드 맥락을 찾기 위해 Incident에서 뽑아내는 값의 전부이다.
 * <p>
 * endpoint와 발생 시각만 있으면 된다. endpoint는 어느 코드를 찾을지를 정하고, 발생 시각은 어느 시점의
 * 코드와 어느 구간의 commit을 볼지를 정한다. Incident Entity를 그대로 넘기면 무엇이 실제로 쓰이는지가
 * 코드에서 사라지고, 나중에 열이 하나 늘었다는 이유만으로 그 값이 조용히 외부 API 호출에 섞인다.
 * <p>
 * service를 넘기지 않는다. 어느 repository를 볼지는 서버 설정이 정하며, service 이름으로 repository를
 * 추측하면 관측 대상이 늘어날 때마다 없는 repository를 조회하게 된다.
 * <p>
 * severity나 status도 넘기지 않는다. 심각도가 높다고 다른 파일을 찾아야 하는 것이 아니고, 이미 해결된
 * Incident라고 코드를 덜 봐야 하는 것도 아니다.
 */
public record IncidentGitHubContextQuery(String endpoint, Instant openedAt) {

	public static IncidentGitHubContextQuery from(Incident incident) {
		return new IncidentGitHubContextQuery(incident.getEndpoint(), incident.getOpenedAt());
	}

}
