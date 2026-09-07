package com.pulseops.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;

/**
 * Consumer Lag이 Prometheus로 나갈 때 무엇을 감추지 않는지와, 관측이 Broker에 얼마나 자주 묻는지를
 * 고정한다.
 * <p>
 * Broker를 띄우지 않는다. offset을 실제로 어떻게 읽는지는
 * {@link KafkaAdminConsumerLagClientIntegrationTest}가 보고 있고, 여기서 확인할 것은 이미 읽어 온 값이
 * 어떤 Gauge가 되는지이다.
 * <p>
 * 다시 읽기를 부르는 쪽 Thread에서 곧바로 실행한다. 비동기 실행이 끝나기를 기다리는 테스트는 느리거나
 * 불안정해지며, 여기서 확인할 것은 어느 Thread에서 도는지가 아니라 무엇을 언제 다시 읽는지이다.
 */
class ConsumerLagMetricsTest {

	private static final String CONSUMER_GROUP = "pulseops-metric-consumer-v1";

	private final FakeKafkaConsumerLagClient lagClient = new FakeKafkaConsumerLagClient();

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private ConsumerLagMetrics consumerLagMetrics;

	@BeforeEach
	void createMetrics() {
		KafkaProperties kafkaProperties = new KafkaProperties();
		kafkaProperties.getConsumer().setGroupId(CONSUMER_GROUP);
		ConsumerLagService consumerLagService = new ConsumerLagService(Optional.of(this.lagClient), kafkaProperties);
		this.consumerLagMetrics = new ConsumerLagMetrics(consumerLagService, this.meterRegistry, Runnable::run);
	}

	/**
	 * 첫 scrape에는 내보낼 값이 없다. Gauge는 마지막으로 읽어 둔 값만 내보내고 자기가 Kafka를 부르지
	 * 않으므로, 처음 물어본 순간에는 다시 읽기를 시작할 뿐 아직 답할 값이 없다. 이것이 Kafka 조회가
	 * scrape를 붙들지 않게 하는 대가이며, 값이 없는 것을 0으로 채우는 것보다 낫다.
	 */
	@Test
	void hasNothingToPublishBeforeTheFirstReadCompletes() {
		assertThat(totalLag()).isNaN();
	}

	/** 두 번째 scrape부터는 Phase 11이 계산한 그 값을 그대로 내보낸다. */
	@Test
	void publishesTheLagPhase11Calculated() {
		this.lagClient.answerWith(List.of(new PartitionOffsets(0, 120L, 100L), new PartitionOffsets(1, 40L, 30L)));

		totalLag();

		assertThat(totalLag()).isEqualTo(30.0);
		assertThat(partitionLag(0)).isEqualTo(20.0);
		assertThat(partitionLag(1)).isEqualTo(10.0);
		assertThat(uninitializedPartitions()).isZero();
	}

	/**
	 * 한 번도 commit되지 않은 partition을 lag 0으로 내보내지 않는다. 그 partition은 밀린 정도를 말할 수
	 * 없는 상태이지 밀리지 않은 상태가 아니며, 0으로 적으면 Consumer가 붙지도 않은 구간이 가장 건강한
	 * 구간으로 보인다.
	 */
	@Test
	void doesNotPublishAnUninitializedPartitionAsZeroLag() {
		this.lagClient.answerWith(List.of(new PartitionOffsets(0, 120L, 100L), new PartitionOffsets(1, 900L, null)));

		totalLag();

		assertThat(partitionLag(1)).isNaN();
		assertThat(uninitializedPartitions()).isEqualTo(1.0);
		assertThat(totalLag()).isEqualTo(20.0);
	}

	/**
	 * Broker에 닿지 못하는 동안 값을 만들어 내지 않는다. 읽지 못한 것을 0으로 답하면 Kafka 장애가
	 * 대시보드에서 가장 건강한 상태로 보인다.
	 */
	@Test
	void publishesNothingWhileKafkaCannotBeRead() {
		this.lagClient.failWith(new ConsumerLagUnavailableException("broker-unreachable", null));

		totalLag();

		assertThat(totalLag()).isNaN();
		assertThat(uninitializedPartitions()).isNaN();
		assertThat(snapshotAgeSeconds()).isNaN();
	}

	/**
	 * 다시 읽기가 실패해도 Gauge를 읽는 쪽으로 예외가 나가지 않는다. Gauge에서 나간 예외는 scrape 응답
	 * 전체를 실패시켜, Kafka와 아무 관계없는 JVM과 HTTP metric까지 함께 사라지게 한다.
	 */
	@Test
	void doesNotLetAKafkaFailureEscapeIntoTheScrape() {
		this.lagClient.failWith(new IllegalStateException("예상하지 못한 오류"));

		assertThat(totalLag()).isNaN();
	}

	/**
	 * Gauge가 셋이고 화면도 같은 값을 보지만, 그 때문에 Broker 조회가 몇 배가 되어서는 안 된다. 관측을
	 * 켰다는 이유로 관측 대상에 부담을 주면 그 관측값은 더 이상 원래 시스템의 상태가 아니다.
	 */
	@Test
	void asksKafkaAtMostOncePerRefreshInterval() {
		totalLag();
		totalLag();
		uninitializedPartitions();
		partitionLag(0);

		assertThat(this.lagClient.requestedTopics()).hasSize(1);
	}

	/** 서버가 정한 Topic과 group만 본다. 관측 경로가 Cluster 안을 들여다보는 통로가 되지 않는다. */
	@Test
	void asksOnlyAboutTheTopicAndGroupThisApplicationConsumes() {
		totalLag();

		assertThat(this.lagClient.requestedTopics()).containsExactly("demo-shop.telemetry.api.v1");
		assertThat(this.lagClient.requestedConsumerGroups()).containsExactly(CONSUMER_GROUP);
	}

	/**
	 * partition 번호 말고는 Tag가 없다. partition 수는 Topic이 정하는 bounded 값이지만 eventId나
	 * requestId를 여기에 붙이면 시계열이 트래픽만큼 늘어난다.
	 */
	@Test
	void tagsConsumerLagMetricsOnlyWithThePartitionNumber() {
		totalLag();
		totalLag();

		List<Meter> lagMeters = this.meterRegistry.getMeters()
			.stream()
			.filter((meter) -> meter.getId().getName().startsWith("pulseops.kafka."))
			.toList();

		assertThat(lagMeters).isNotEmpty();
		assertThat(lagMeters).allSatisfy((meter) -> assertThat(meter.getId().getTags())
			.extracting(Tag::getKey)
			.isSubsetOf(ConsumerLagMetrics.PARTITION_TAG));
	}

	private double totalLag() {
		return this.meterRegistry.get(ConsumerLagMetrics.TOTAL_LAG_GAUGE).gauge().value();
	}

	private double uninitializedPartitions() {
		return this.meterRegistry.get(ConsumerLagMetrics.UNINITIALIZED_PARTITIONS_GAUGE).gauge().value();
	}

	private double snapshotAgeSeconds() {
		return this.meterRegistry.get(ConsumerLagMetrics.SNAPSHOT_AGE_GAUGE).gauge().value();
	}

	/**
	 * partition별 Gauge는 한 번이라도 읽어 온 뒤에야 존재한다. 아직 partition 목록을 모르는 상태에서
	 * 줄을 만들어 두면 있지도 않은 partition을 보고하게 된다.
	 */
	private double partitionLag(int partition) {
		try {
			return this.meterRegistry.get(ConsumerLagMetrics.PARTITION_LAG_GAUGE)
				.tag(ConsumerLagMetrics.PARTITION_TAG, Integer.toString(partition))
				.gauge()
				.value();
		}
		catch (MeterNotFoundException ex) {
			return Double.NaN;
		}
	}

}
