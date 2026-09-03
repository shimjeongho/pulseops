package com.pulseops.incident;

import java.time.Duration;
import java.time.Instant;

/**
 * 해결된 Incident 하나가 열려 있던 구간이다. Daily Report가 해결 시간을 집계할 때 Incident를 통째로
 * 읽지 않으려고 두 시각만 뽑아 온다.
 * <p>
 * 집계에 필요한 것이 두 시각뿐인데 Entity를 전부 읽으면 쓰이지 않는 열까지 함께 오고, 그 Entity들이
 * 영속성 Context에 쌓인다. 하루치라 지금은 문제가 되지 않지만 그것이 이 조회가 무엇을 필요로 하는지를
 * 흐린다.
 * <p>
 * 열려 있던 시간을 계산하는 곳도 여기이다. 두 시각을 들고 있는 자리가 그 차이의 뜻도 함께 갖는 편이,
 * 집계하는 쪽이 매번 같은 뺄셈을 다시 적는 것보다 낫다.
 */
public record IncidentResolution(Instant openedAt, Instant resolvedAt) {

	/**
	 * 초 미만은 버린다. Incident는 사람이 붙어 처리하는 기록이라 해결 시간의 의미 있는 단위가 초이며,
	 * 밀리초까지 남겨도 읽는 사람에게 달라지는 판단이 없다.
	 */
	public long durationSeconds() {
		return Duration.between(this.openedAt, this.resolvedAt).toSeconds();
	}

}
