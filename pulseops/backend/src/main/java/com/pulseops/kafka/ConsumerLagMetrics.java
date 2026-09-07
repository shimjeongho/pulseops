package com.pulseops.kafka;

import com.pulseops.kafka.dto.ConsumerLagPartitionResponse;
import com.pulseops.kafka.dto.ConsumerLagResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Consumer Lag을 Prometheus가 읽을 수 있는 형태로 내보낸다.
 * <p>
 * offset을 다시 읽거나 lag을 다시 계산하지 않는다. 무엇을 어떻게 세는지는 Phase 11의
 * {@link ConsumerLagService}와 {@link ConsumerLagResponse}가 이미 정해 두었고, 여기서는 그 결과를
 * 받아 Gauge에 옮기기만 한다. 계산을 한 벌 더 만들면 REST 화면의 숫자와 Prometheus의 숫자가
 * 갈라질 수 있고, 그때 어느 쪽이 맞는지 말할 근거가 없다.
 * <p>
 * Lag은 관측값이다. 이 Class가 만드는 어떤 값도 Incident 판정에 들어가지 않으며, Consumer의 offset을
 * commit하거나 되돌리거나 멈추지 않는다.
 *
 * <h2>왜 Gauge 안에서 Kafka를 부르지 않는가</h2>
 *
 * Gauge를 읽을 때마다 Kafka Admin을 부르면 두 가지가 무너진다. 첫째, 여기서 내보내는 Gauge는 셋이라
 * Prometheus가 한 번 긁을 때마다 같은 조회가 세 번 나간다. 화면이 5초마다 부르는 REST까지 더하면
 * 관측을 켰다는 이유만으로 Broker에 대한 조회가 몇 배가 된다. 둘째, 그 호출은 Broker에 닿지 못할 때
 * 최대 5초까지 응답을 기다린다. Kafka가 아픈 동안 그 기다림이 scrape를 붙들면, Kafka와 아무 상관없는
 * JVM과 HTTP와 Incident metric까지 함께 관측되지 않는다. Kafka 장애가 관측 전체의 장애가 되는 것이다.
 * <p>
 * 그래서 Gauge는 마지막으로 읽어 둔 값만 내보내고, 값이 오래되었으면 다시 읽어 오라고 신호만
 * 남긴다. 실제 조회는 다른 Thread에서 일어나므로 scrape는 Kafka의 상태와 무관하게 즉시 끝난다.
 *
 * <h2>왜 Scheduler가 아닌가</h2>
 *
 * PulseOps에서 Scheduler는 Daily Report처럼 시간이 오면 해야 하는 일에 쓴다. Consumer Lag을 주기적으로
 * 읽는 Scheduler를 두면 아무도 보지 않는 시간에도 Broker에 조회가 나가고, 그 주기와 Prometheus의 scrape
 * 주기가 서로를 모른 채 각자 돈다. 여기서 다시 읽는 계기는 언제나 누군가 값을 물어봤다는 사실이다.
 *
 * <h2>낡은 값을 다루는 방법</h2>
 *
 * 다시 읽기에 실패해도 마지막 값을 계속 내보내면 Broker 장애가 대시보드에서 "lag 0"으로 굳는다.
 * 그래서 {@link #PUBLISH_MAX_AGE}보다 오래된 값은 내보내지 않고 NaN으로 남긴다. NaN은 Prometheus에서
 * 값이 없는 구간이 되어 그래프가 끊기므로, 읽지 못하고 있다는 사실이 드러난다.
 * <p>
 * 그 사이 구간을 위해 마지막으로 읽은 지 얼마나 지났는지도 함께 내보낸다. 값은 아직 있지만 낡아
 * 가고 있는 상태를 대시보드에서 구분할 수 있어야 하기 때문이다.
 * <p>
 * REST {@code GET /api/kafka/consumer-lag}은 그대로 둔다. 그 경로는 부를 때마다 Kafka에게 직접 묻고
 * 그 순간의 값을 돌려주기로 되어 있으며, 여기의 snapshot을 그쪽에 끼워 넣으면 사람이 새로고침으로
 * 확인한 값이 최대 몇 초 전 것이 된다. 두 경로는 같은 {@link ConsumerLagService}를 통해 같은 방식으로
 * 계산된 값을 보되, 언제 읽은 값인지는 각자의 사정대로 갖는다.
 */
@Component
public class ConsumerLagMetrics {

	static final String TOTAL_LAG_GAUGE = "pulseops.kafka.consumer.lag";

	static final String PARTITION_LAG_GAUGE = "pulseops.kafka.consumer.partition.lag";

	static final String UNINITIALIZED_PARTITIONS_GAUGE = "pulseops.kafka.consumer.partitions.uninitialized";

	static final String SNAPSHOT_AGE_GAUGE = "pulseops.kafka.consumer.lag.snapshot.age";

	static final String PARTITION_TAG = "partition";

	/**
	 * 이보다 오래된 값을 물어보면 다시 읽어 오게 한다.
	 * <p>
	 * Prometheus의 scrape 간격과 같은 5초로 둔다. 더 짧게 두면 한 번의 scrape 안에서도 다시 읽기가
	 * 시작되어 Broker 조회가 scrape보다 잦아지고, 더 길게 두면 연속한 scrape가 같은 값을 보고한다.
	 */
	private static final Duration REFRESH_AFTER = Duration.ofSeconds(5);

	/**
	 * 이보다 오래된 값은 내보내지 않는다.
	 * <p>
	 * 다시 읽기가 몇 번 연속으로 실패해도 잠깐은 마지막 값을 보여 주는 편이 낫다. Broker의 순간적인
	 * 흔들림마다 그래프가 끊기면 정말로 닿지 못하는 구간을 알아볼 수 없기 때문이다. 다만 그 관용을
	 * 다시 읽기 주기의 몇 배로 제한해서, 이어지는 실패는 반드시 값의 부재로 드러나게 한다.
	 */
	private static final Duration PUBLISH_MAX_AGE = Duration.ofSeconds(20);

	private static final Logger logger = LoggerFactory.getLogger(ConsumerLagMetrics.class);

	private final ConsumerLagService consumerLagService;

	private final Executor refreshExecutor;

	private final MultiGauge partitionLagGauge;

	private final AtomicReference<ConsumerLagSnapshot> snapshot = new AtomicReference<>();

	/** 다시 읽기가 겹치지 않게 한다. 한 번에 하나만 나가면 Broker 조회 수가 scrape 수를 넘지 않는다. */
	private final AtomicBoolean refreshing = new AtomicBoolean();

	/**
	 * 생성자가 둘이므로 Spring이 쓸 것을 지목한다. 표시하지 않으면 어느 것으로도 만들 수 없다.
	 */
	@Autowired
	public ConsumerLagMetrics(ConsumerLagService consumerLagService, MeterRegistry meterRegistry) {
		this(consumerLagService, meterRegistry, newRefreshExecutor());
	}

	/**
	 * 테스트는 자기 Thread에서 곧바로 실행하는 {@link Executor}를 넘겨 다시 읽기가 끝난 시점을
	 * 확정할 수 있다. 비동기 실행이 언제 끝나는지 기다리는 테스트는 느리거나 불안정해진다.
	 */
	ConsumerLagMetrics(ConsumerLagService consumerLagService, MeterRegistry meterRegistry, Executor refreshExecutor) {
		this.consumerLagService = consumerLagService;
		this.refreshExecutor = refreshExecutor;
		this.partitionLagGauge = MultiGauge.builder(PARTITION_LAG_GAUGE)
			.description("Uncommitted records per partition; absent while a partition has no committed offset")
			.register(meterRegistry);
		registerScalarGauges(meterRegistry);
	}

	/**
	 * 자기가 만든 Thread만 정리한다. 테스트가 넘긴 {@link Executor}는 {@link ExecutorService}가
	 * 아니므로 아무 일도 일어나지 않는다.
	 */
	@PreDestroy
	void shutdown() {
		if (this.refreshExecutor instanceof ExecutorService executorService) {
			executorService.shutdownNow();
		}
	}

	private void registerScalarGauges(MeterRegistry meterRegistry) {
		Gauge.builder(TOTAL_LAG_GAUGE, this, ConsumerLagMetrics::totalLag)
			.description("Uncommitted records summed over partitions that have a committed offset")
			.register(meterRegistry);
		Gauge.builder(UNINITIALIZED_PARTITIONS_GAUGE, this, ConsumerLagMetrics::uninitializedPartitions)
			.description("Partitions whose lag is unknown because the consumer group has never committed there")
			.register(meterRegistry);
		Gauge.builder(SNAPSHOT_AGE_GAUGE, this, ConsumerLagMetrics::snapshotAgeSeconds)
			.description("Seconds since the published consumer lag values were read from Kafka")
			.baseUnit("seconds")
			.register(meterRegistry);
	}

	/**
	 * 합계는 lag을 계산할 수 있었던 partition만 더한 값이다. 한 번도 commit되지 않은 partition은 0으로
	 * 더해지지 않으며, 그런 partition이 몇 개인지는 {@link #uninitializedPartitions()}가 따로 답한다.
	 * 그렇게 하지 않으면 Consumer가 아예 붙지 않은 Topic이 가장 건강한 Topic으로 보인다.
	 */
	private double totalLag() {
		ConsumerLagResponse published = publishedLag();
		return (published != null) ? published.totalLag() : Double.NaN;
	}

	private double uninitializedPartitions() {
		ConsumerLagResponse published = publishedLag();
		return (published != null) ? published.uninitializedPartitionCount() : Double.NaN;
	}

	/**
	 * 값이 없으면 나이도 없다. 0으로 답하면 한 번도 읽지 못한 상태가 방금 읽은 상태처럼 보인다.
	 */
	private double snapshotAgeSeconds() {
		ConsumerLagSnapshot current = this.snapshot.get();
		return (current != null) ? current.ageSeconds(Instant.now()) : Double.NaN;
	}

	/**
	 * partition별 lag이다. 한 번도 commit되지 않은 partition은 0이 아니라 NaN으로 남긴다. 그 partition은
	 * 밀린 정도를 말할 수 없는 상태이지 밀리지 않은 상태가 아니다.
	 */
	private double partitionLag(int partition) {
		ConsumerLagResponse published = publishedLag();
		if (published == null) {
			return Double.NaN;
		}
		for (ConsumerLagPartitionResponse each : published.partitions()) {
			if (each.partition() == partition) {
				return (each.lag() != null) ? each.lag() : Double.NaN;
			}
		}
		return Double.NaN;
	}

	/**
	 * 내보낼 수 있는 값을 돌려주고, 필요하면 다시 읽기를 시작한다. 값이 너무 낡았으면 null이다.
	 * <p>
	 * 이 method가 Gauge와 Kafka 사이의 유일한 접점이다. 낡음을 판단하는 자리를 하나로 모아야 네
	 * Gauge가 같은 순간에 같은 판단을 한다.
	 */
	private ConsumerLagResponse publishedLag() {
		Instant now = Instant.now();
		ConsumerLagSnapshot current = this.snapshot.get();
		if (current == null || current.isOlderThan(REFRESH_AFTER, now)) {
			triggerRefresh();
		}
		return (current != null && !current.isOlderThan(PUBLISH_MAX_AGE, now)) ? current.lag() : null;
	}

	private void triggerRefresh() {
		if (!this.refreshing.compareAndSet(false, true)) {
			return;
		}
		try {
			this.refreshExecutor.execute(this::refresh);
		}
		catch (RejectedExecutionException ex) {
			// Application이 내려가는 중이라 다시 읽을 자리가 없다. 이 예외를 위로 올리면 Gauge를 읽던
			// scrape 전체가 실패하는데, 그것은 종료 중이라는 사실을 관측의 오류로 바꾸는 것이다.
			this.refreshing.set(false);
			logger.debug("Consumer Lag metric 갱신을 시작하지 못했다. 종료 중일 수 있다.");
		}
	}

	/**
	 * 실패하면 이전 snapshot을 그대로 둔다. 실패를 빈 값으로 덮으면 잠깐의 흔들림에도 그래프가 끊기고,
	 * 반대로 영원히 두지도 않는다. {@link #PUBLISH_MAX_AGE}가 지나면 내보내는 쪽에서 걸러진다.
	 */
	private void refresh() {
		try {
			ConsumerLagResponse lag = this.consumerLagService.currentLag();
			this.snapshot.set(new ConsumerLagSnapshot(lag, Instant.now()));
			registerPartitionGauges(lag);
		}
		catch (ConsumerLagUnavailableException ex) {
			logger.warn("Consumer Lag을 읽지 못해 metric을 갱신하지 못했다. reason={}", ex.getReason());
		}
		catch (RuntimeException ex) {
			logger.warn("Consumer Lag metric 갱신 중 예상하지 못한 오류가 났다. reason={}",
					ex.getClass().getSimpleName());
		}
		finally {
			this.refreshing.set(false);
		}
	}

	/**
	 * partition 목록이 바뀔 수 있으므로 읽을 때마다 다시 등록한다. Topic의 partition은 운영 중에 늘어날
	 * 수 있고, 줄어든 partition의 시계열이 남아 있으면 없는 partition이 계속 보고된다.
	 * <p>
	 * 각 줄은 값을 복사해 두지 않고 이 객체에게 다시 묻는다. 그래야 값이 낡아 내보낼 수 없게 됐을 때
	 * partition별 줄도 합계와 같은 순간에 함께 비워진다.
	 */
	private void registerPartitionGauges(ConsumerLagResponse lag) {
		List<MultiGauge.Row<?>> rows = new ArrayList<>(lag.partitions().size());
		for (ConsumerLagPartitionResponse each : lag.partitions()) {
			int partition = each.partition();
			rows.add(MultiGauge.Row.of(Tags.of(PARTITION_TAG, Integer.toString(partition)), this,
					(metrics) -> metrics.partitionLag(partition)));
		}
		this.partitionLagGauge.register(rows, true);
	}

	/**
	 * 하나뿐인 daemon Thread에 맡긴다. 이 Thread가 하는 일은 몇 초에 한 번 Kafka에게 offset을 묻는
	 * 것뿐이라 더 필요하지 않고, daemon이라 Application 종료를 붙들지 않는다.
	 */
	private static ExecutorService newRefreshExecutor() {
		return Executors.newSingleThreadExecutor((runnable) -> {
			Thread thread = new Thread(runnable, "consumer-lag-metrics");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * @param lag Phase 11 계약 그대로의 조회 결과
	 * @param readAt 그 값을 Kafka에서 읽은 시각. 응답 안의 {@code generatedAt}과 같은 뜻이지만, 여기서
	 * 다시 들고 있는 것은 낡음을 판단하는 기준이 응답의 내용이 아니라 이 snapshot의 성질이기 때문이다.
	 */
	private record ConsumerLagSnapshot(ConsumerLagResponse lag, Instant readAt) {

		boolean isOlderThan(Duration age, Instant now) {
			return this.readAt.plus(age).isBefore(now);
		}

		double ageSeconds(Instant now) {
			return Duration.between(this.readAt, now).toMillis() / 1000.0;
		}

	}

}
