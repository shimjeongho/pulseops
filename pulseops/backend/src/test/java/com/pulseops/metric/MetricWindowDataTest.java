package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 읽어 온 표본이 나중에 바뀌지 않는지 확인한다. */
class MetricWindowDataTest {

	@Test
	void doesNotFollowLaterChangesToTheGivenSampleList() {
		List<Long> latencySamples = new ArrayList<>(List.of(10L, 20L));
		MetricWindowData data = new MetricWindowData(2, 0, latencySamples);

		latencySamples.add(30L);

		assertThat(data.latencySamples()).containsExactly(10L, 20L);
	}

	@Test
	void exposesSamplesThatCannotBeModified() {
		MetricWindowData data = new MetricWindowData(1, 0, new ArrayList<>(List.of(10L)));

		assertThatThrownBy(() -> data.latencySamples().add(20L))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
