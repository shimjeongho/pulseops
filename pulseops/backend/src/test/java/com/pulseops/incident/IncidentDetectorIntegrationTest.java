package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricSnapshot;
import com.pulseops.metric.MetricWindow;
import com.pulseops.metric.RollingMetricService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 창 판정이 실제 MySQL 위에서 Detection State와 Incident로 이어지는 흐름을 검증한다.
 * <p>
 * Redis만 대역으로 세운다. Bucket을 읽어 오류율과 P95를 만드는 계산은 Phase 3에서 이미 검증했고,
 * 여기서 볼 것은 그 결과가 어떤 상태 변화를 만드는가이다. 반대로 MySQL은 대역으로 두지 않는다.
 * 이 Class가 확인하려는 것 대부분이 행 잠금, identity 제약, Dirty Checking처럼 실제 DB에서만
 * 성립하는 성질이라, Repository를 흉내 내면 통과해도 아무것도 보증하지 않는다.
 * <p>
 * 검증 대상을 Context에서 꺼내지 않고 직접 만든다. Redis 대역을 Bean으로 바꿔 끼우면 다른 테스트와
 * 다른 Context가 되고, 그 차이가 이 테스트가 보려는 것과 아무 상관이 없다.
 * <p>
 * 어느 test method도 Transaction 안에서 돌지 않는다. Detection이 스스로 여는 Transaction이 실제로
 * commit되어야 하고, 확인은 그 다음의 실제 SELECT여야 하기 때문이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentDetectorIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

	/** 연속된 창은 Bucket 하나만큼 떨어져 있다. 이 값들이 곧 연속의 정의이다. */
	private static final Instant WINDOW_1 = Instant.parse("2026-08-31T12:00:30Z");

	private static final Instant WINDOW_2 = WINDOW_1.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final Instant WINDOW_3 = WINDOW_2.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final Instant WINDOW_4 = WINDOW_3.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final Instant WINDOW_5 = WINDOW_4.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final Instant WINDOW_6 = WINDOW_5.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final Instant WINDOW_7 = WINDOW_6.plusSeconds(MetricWindow.BUCKET_SECONDS);

	/** 판정에 필요한 최소 요청 수를 넘긴 값이다. */
	private static final long ENOUGH_REQUESTS = 100;

	private static final long TOO_FEW_REQUESTS = 10;

	private static final long WARNING_ERROR_COUNT = 15;

	private static final long CRITICAL_ERROR_COUNT = 30;

	private static final long NEUTRAL_ERROR_COUNT = 5;

	private static final Long HEALTHY_LATENCY_MS = 100L;

	private static final Long NEUTRAL_LATENCY_MS = 800L;

	private static final Long CRITICAL_LATENCY_MS = 4000L;

	/** 창이 닫힌 뒤 아직 다음 Bucket이 채워지는 중인 시각이다. 판정은 이 Bucket을 보면 안 된다. */
	private static final int SECONDS_INTO_THE_FILLING_BUCKET = 7;

	private final RollingMetricService rollingMetricService = mock(RollingMetricService.class);

	/**
	 * 판정이 알린 변화를 순서대로 모아 둔다. 실제 전달 수단을 세우지 않는 것은 여기서 볼 것이 "언제
	 * 알리는가"이지 "어떻게 보내는가"가 아니기 때문이다. 알림을 받은 뒤 무엇을 하는지는
	 * {@link IncidentChangedEventListener}의 몫이고 그쪽 테스트가 따로 본다.
	 * <p>
	 * 판정은 이것 말고 {@link IncidentLifecycleEvent}도 알리지만 여기서는 걸러 낸다. 그쪽은 관측값을 세기
	 * 위한 것이라 알림의 시점이나 횟수를 이 테스트가 정하지 않으며, 무엇을 세는지는
	 * {@link IncidentMetricsTest}가 본다.
	 */
	private final List<IncidentChangedEvent> announcedChanges = new ArrayList<>();

	private final ApplicationEventPublisher eventPublisher = (event) -> {
		if (event instanceof IncidentChangedEvent changed) {
			this.announcedChanges.add(changed);
		}
	};

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private IncidentDetector detector;

	@BeforeEach
	void createDetector() {
		this.detector = new IncidentDetector(this.rollingMetricService, this.detectionStateRepository,
				this.incidentRepository, this.transactionTemplate, this.eventPublisher);
	}

	@AfterEach
	void removeDetectionResults() {
		this.incidentRepository.deleteAll();
		this.detectionStateRepository.deleteAll();
	}

	/**
	 * 채워지는 중인 Bucket이 판정에 들어오면 같은 구간의 오류율이 볼 때마다 달라진다. 그래서 기준
	 * 시각이 아니라 그 한 칸 앞을 저장소에 넘긴다.
	 */
	@Test
	void readsTheLastWindowThatHasAlreadyClosed() {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(healthy(WINDOW_1));

		this.detector.detect(SERIES, WINDOW_1.plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET));

		ArgumentCaptor<Instant> reference = ArgumentCaptor.forClass(Instant.class);
		then(this.rollingMetricService).should().snapshot(eq(SERIES), reference.capture());
		assertThat(reference.getValue()).isEqualTo(WINDOW_1.minusSeconds(MetricWindow.BUCKET_SECONDS));
	}

	/** 창 하나가 나쁘다고 Incident를 열지 않는다. 한 번 튄 값과 이어지는 이상은 다른 사실이다. */
	@Test
	void doesNotOpenAnIncidentOnTheFirstAbnormalWindow() {
		detect(errorRateWarning(WINDOW_1));

		assertThat(state(IncidentType.ERROR_RATE).getAbnormalConsecutive()).isEqualTo(1);
		assertThat(this.incidentRepository.count()).isZero();
	}

	/** 두 번 연속 이상이면 연다. 근거로 남는 값은 연속을 완성한 창의 관측값이다. */
	@Test
	void opensAnIncidentOnTheSecondConsecutiveAbnormalWindow() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		assertThat(state(IncidentType.ERROR_RATE).getAbnormalConsecutive()).isEqualTo(2);
		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
			assertThat(incident.getOpenedAt()).isEqualTo(WINDOW_2);
			assertThat(incident.getUpdatedAt()).isEqualTo(WINDOW_2);
			assertThat(incident.getTriggerWindowStart()).isEqualTo(WINDOW_2.minusSeconds(60));
			assertThat(incident.getTriggerWindowEnd()).isEqualTo(WINDOW_2);
			assertThat(incident.getTriggerRequestCount()).isEqualTo(ENOUGH_REQUESTS);
			assertThat(incident.getTriggerErrorCount()).isEqualTo(WARNING_ERROR_COUNT);
		});
	}

	/** 열릴 때의 심각도는 앞 창이 아니라 연속을 완성한 창의 것이다. 사람이 먼저 볼 값은 지금 상태이다. */
	@Test
	void opensWithTheSeverityOfTheWindowThatCompletedTheStreak() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateCritical(WINDOW_2));

		assertThat(incident(IncidentType.ERROR_RATE)).get()
			.extracting(Incident::getSeverity)
			.isEqualTo(IncidentSeverity.CRITICAL);
	}

	/** 앞 창이 더 나빴더라도 마찬가지다. 지나간 최악의 값을 기억해 두었다가 쓰지 않는다. */
	@Test
	void doesNotCarryTheSeverityOfTheEarlierWindow() {
		detect(errorRateCritical(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		assertThat(incident(IncidentType.ERROR_RATE)).get()
			.extracting(Incident::getSeverity)
			.isEqualTo(IncidentSeverity.WARNING);
	}

	/** 이상이 계속돼도 기록은 하나다. 같은 장애가 창 수만큼 늘어나면 무엇이 진짜인지 알 수 없다. */
	@Test
	void keepsASingleIncidentWhileTheAbnormalWindowsContinue() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		detect(errorRateWarning(WINDOW_3));
		detect(errorRateWarning(WINDOW_4));

		assertThat(this.incidentRepository.count()).isEqualTo(1);
		assertThat(incident(IncidentType.ERROR_RATE)).get()
			.extracting(Incident::getSeverity)
			.isEqualTo(IncidentSeverity.WARNING);
	}

	/** 이미 열린 장애가 더 나빠진 것은 한 창만으로도 알려야 한다. 연속을 다시 세지 않는다. */
	@Test
	void raisesTheSeverityOnASingleWorseWindow() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		Long openedId = incident(IncidentType.ERROR_RATE).orElseThrow().getId();

		detect(errorRateCritical(WINDOW_3));

		assertThat(this.incidentRepository.count()).isEqualTo(1);
		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getId()).isEqualTo(openedId);
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
			assertThat(incident.getUpdatedAt()).isEqualTo(WINDOW_3);
		});
	}

	/**
	 * 심각도는 이 Incident가 도달했던 가장 나쁜 지점을 뜻하므로 내려가지 않는다. 조금 나아진 창을
	 * 예외로 만들지도 않는다. 아직 이상인 창이 오는 것은 정상이다.
	 */
	@Test
	void neverLowersTheSeverityOfAnActiveIncident() {
		detect(errorRateCritical(WINDOW_1));
		detect(errorRateCritical(WINDOW_2));

		detect(errorRateWarning(WINDOW_3));

		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
			assertThat(incident.getUpdatedAt()).isEqualTo(WINDOW_2);
		});
	}

	/** 사람이 확인 중이어도 악화 사실은 기록되어야 한다. 확인은 상태이지 장애의 크기가 아니다. */
	@Test
	void raisesTheSeverityOfAnAcknowledgedIncident() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		acknowledge(WINDOW_2.plusSeconds(5));

		detect(errorRateCritical(WINDOW_3));

		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		});
	}

	/** 회복은 여는 기준보다 길게 확인한다. 두 번째 정상 창까지는 아직 끝난 것이 아니다. */
	@Test
	void resolvesOnlyAfterThreeConsecutiveHealthyWindows() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(healthy(WINDOW_3));
		assertThat(incident(IncidentType.ERROR_RATE)).get().extracting(Incident::getStatus).isEqualTo(
				IncidentStatus.OPEN);

		detect(healthy(WINDOW_4));
		assertThat(incident(IncidentType.ERROR_RATE)).get().extracting(Incident::getStatus).isEqualTo(
				IncidentStatus.OPEN);

		detect(healthy(WINDOW_5));
		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
			assertThat(incident.getResolvedAt()).isEqualTo(WINDOW_5);
			assertThat(incident.getUpdatedAt()).isEqualTo(WINDOW_5);
		});
	}

	/** 회복은 서비스의 사실이라 사람이 확인했는지와 무관하다. 확인했다는 사실 자체는 지우지 않는다. */
	@Test
	void resolvesAnAcknowledgedIncidentToo() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		Instant acknowledgedAt = WINDOW_2.plusSeconds(5);
		acknowledge(acknowledgedAt);

		detect(healthy(WINDOW_3));
		detect(healthy(WINDOW_4));
		detect(healthy(WINDOW_5));

		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
			assertThat(incident.getAcknowledgedAt()).isEqualTo(acknowledgedAt);
			assertThat(incident.getResolvedAt()).isEqualTo(WINDOW_5);
		});
	}

	/** 기준선 사이의 창은 좋아졌다는 근거가 아니다. 그것으로 회복이 이어지면 완충 구간이 사라진다. */
	@Test
	void breaksTheRecoveryStreakOnANeutralWindow() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(healthy(WINDOW_3));
		detect(neutral(WINDOW_4));
		detect(healthy(WINDOW_5));

		assertThat(state(IncidentType.ERROR_RATE).getRecoveryConsecutive()).isEqualTo(1);
		assertThat(incident(IncidentType.ERROR_RATE)).get().extracting(Incident::getStatus).isEqualTo(
				IncidentStatus.OPEN);
	}

	/** 트래픽이 없어 판정하지 못한 창도 마찬가지다. 모른다는 것은 좋아졌다는 뜻이 아니다. */
	@Test
	void breaksTheRecoveryStreakOnAnInconclusiveWindow() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(healthy(WINDOW_3));
		detect(lowTraffic(WINDOW_4));
		detect(healthy(WINDOW_5));

		assertThat(state(IncidentType.ERROR_RATE).getRecoveryConsecutive()).isEqualTo(1);
		assertThat(incident(IncidentType.ERROR_RATE)).get().extracting(Incident::getStatus).isEqualTo(
				IncidentStatus.OPEN);
	}

	/** 요청이 끊긴 서비스를 회복으로 세면 아직 아픈 계열의 Incident가 조용히 닫힌다. */
	@Test
	void keepsTheIncidentOpenWhenTrafficDisappears() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(lowTraffic(WINDOW_3));

		assertThat(state(IncidentType.ERROR_RATE)).satisfies((state) -> {
			assertThat(state.getAbnormalConsecutive()).isZero();
			assertThat(state.getRecoveryConsecutive()).isZero();
		});
		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getResolvedAt()).isNull();
		});
	}

	/**
	 * 요청이 충분해도 P95를 구하지 못한 창은 응답 시간을 판정하지 못한다. 그 창에서 오류율은 정상적으로
	 * 판정되므로 두 종류는 여기서 서로 다른 길을 간다.
	 */
	@Test
	void keepsTheLatencyIncidentOpenWhenThePercentileIsMissing() {
		detect(latencyCritical(WINDOW_1));
		detect(latencyCritical(WINDOW_2));

		detect(missingLatency(WINDOW_3));

		assertThat(state(IncidentType.LATENCY)).satisfies((state) -> {
			assertThat(state.getAbnormalConsecutive()).isZero();
			assertThat(state.getRecoveryConsecutive()).isZero();
		});
		assertThat(incident(IncidentType.LATENCY)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getResolvedAt()).isNull();
		});
		assertThat(state(IncidentType.ERROR_RATE).getRecoveryConsecutive()).isEqualTo(3);
	}

	/** 보지 못한 구간이 있으면 그 앞의 연속은 근거가 되지 못한다. 다만 지금 창은 새 연속의 첫 창이 된다. */
	@Test
	void startsANewStreakAfterAMissedWindow() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_3));

		assertThat(state(IncidentType.ERROR_RATE).getAbnormalConsecutive()).isEqualTo(1);
		assertThat(this.incidentRepository.count()).isZero();

		detect(errorRateWarning(WINDOW_4));

		assertThat(state(IncidentType.ERROR_RATE).getAbnormalConsecutive()).isEqualTo(2);
		assertThat(this.incidentRepository.count()).isEqualTo(1);
	}

	/**
	 * 같은 창이 다시 오는 것은 at-least-once 전달에서 정상이다. 그것이 두 번 세어지면 한 번만 나빴던
	 * 창으로 Incident가 열린다.
	 */
	@Test
	void countsTheSameWindowOnlyOnce() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_1));

		assertThat(state(IncidentType.ERROR_RATE)).satisfies((state) -> {
			assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
			assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(WINDOW_1);
		});
		assertThat(this.incidentRepository.count()).isZero();
	}

	/** 이미 Incident가 열린 뒤에 같은 창이 다시 와도 기록이 늘지 않는다. */
	@Test
	void doesNotDuplicateAnIncidentWhenTheSameWindowArrivesAgain() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		Long openedId = incident(IncidentType.ERROR_RATE).orElseThrow().getId();

		detect(errorRateWarning(WINDOW_2));

		assertThat(this.incidentRepository.count()).isEqualTo(1);
		assertThat(incident(IncidentType.ERROR_RATE)).get().extracting(Incident::getId).isEqualTo(openedId);
	}

	/** 같은 문제가 다시 나면 끝난 기록을 되살리지 않고 새로 연다. 그래야 장애의 구간이 남는다. */
	@Test
	void opensANewIncidentWhenTheProblemReturns() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		Long firstId = incident(IncidentType.ERROR_RATE).orElseThrow().getId();
		detect(healthy(WINDOW_3));
		detect(healthy(WINDOW_4));
		detect(healthy(WINDOW_5));

		detect(errorRateWarning(WINDOW_6));
		detect(errorRateWarning(WINDOW_7));

		List<Incident> incidents = this.incidentRepository.findAll();
		assertThat(incidents).hasSize(2);
		assertThat(incidents).filteredOn((incident) -> incident.getId().equals(firstId))
			.singleElement()
			.extracting(Incident::getStatus)
			.isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident(IncidentType.ERROR_RATE)).get().satisfies((reopened) -> {
			assertThat(reopened.getId()).isNotEqualTo(firstId);
			assertThat(reopened.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(reopened.getOpenedAt()).isEqualTo(WINDOW_7);
		});
	}

	/** 오류율과 응답 시간은 서로 다른 이상이므로 한 계열에서 동시에 열린다. */
	@Test
	void opensOneIncidentPerTypeWhenBothGoBad() {
		detect(bothCritical(WINDOW_1));
		detect(bothCritical(WINDOW_2));

		assertThat(this.incidentRepository.findAll()).hasSize(2)
			.allSatisfy((incident) -> assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL))
			.extracting(Incident::getType)
			.containsExactlyInAnyOrder(IncidentType.ERROR_RATE, IncidentType.LATENCY);
	}

	/** 한 종류가 나빠도 다른 종류의 회복은 그대로 쌓인다. 두 상태는 서로를 막지 않는다. */
	@Test
	void tracksEachTypeIndependently() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		assertThat(state(IncidentType.ERROR_RATE).getAbnormalConsecutive()).isEqualTo(2);
		assertThat(state(IncidentType.LATENCY)).satisfies((state) -> {
			assertThat(state.getAbnormalConsecutive()).isZero();
			assertThat(state.getRecoveryConsecutive()).isEqualTo(2);
		});
		assertThat(incident(IncidentType.LATENCY)).isEmpty();
	}

	/**
	 * Metric을 읽지 못하면 실패를 그대로 올려 보낸다. Phase 4에서는 삼켰지만, 이제는 Consumer의 재시도가
	 * 같은 Event로 판정을 다시 시도할 수 있어 실패를 알리는 편이 낫다.
	 * <p>
	 * 실패한 판정은 아무 상태도 남기지 않는다. 그래야 재시도가 같은 창을 처음부터 다시 볼 수 있다.
	 */
	@Test
	void propagatesTheFailureWhenMetricsCannotBeRead() {
		given(this.rollingMetricService.snapshot(eq(SERIES), any()))
			.willThrow(new RedisConnectionFailureException("연결 실패"));

		assertThatExceptionOfType(RedisConnectionFailureException.class).isThrownBy(
				() -> this.detector.detect(SERIES, WINDOW_1.plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET)));

		assertThat(this.detectionStateRepository.count()).isZero();
		assertThat(this.incidentRepository.count()).isZero();
	}

	/**
	 * MySQL이 판정 결과를 받지 못해도 마찬가지로 올려 보낸다.
	 * <p>
	 * endpoint를 열 길이보다 길게 만들어 저장 자체를 실패시킨다. 실패 지점이 Detection State를 처음
	 * 만드는 자리라, 되돌아간 Transaction이 아무 흔적도 남기지 않는 것까지 함께 확인된다.
	 */
	@Test
	void propagatesTheFailureWhenTheResultCannotBeStored() {
		MetricSeries tooLong = new MetricSeries(SERVICE, METHOD, "/api/" + "x".repeat(300));
		given(this.rollingMetricService.snapshot(eq(tooLong), any()))
			.willReturn(new MetricSnapshot(tooLong.service(), tooLong.method(), tooLong.endpoint(),
					WINDOW_1.minusSeconds(60), WINDOW_1, ENOUGH_REQUESTS, WARNING_ERROR_COUNT, 15.0,
					HEALTHY_LATENCY_MS));

		assertThatExceptionOfType(DataAccessException.class).isThrownBy(
				() -> this.detector.detect(tooLong, WINDOW_1.plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET)));

		assertThat(this.detectionStateRepository.count()).isZero();
		assertThat(this.incidentRepository.count()).isZero();
	}

	/** 저장소 장애가 아닌 결함도 그대로 올라가야 열려야 할 Incident가 조용히 열리지 않는 일이 없다. */
	@Test
	void doesNotHideAFailureThatIsNotAStorageFailure() {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willThrow(new IllegalStateException("결함"));

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(
				() -> this.detector.detect(SERIES, WINDOW_1.plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET)));
	}

	/**
	 * Incident가 새로 열리는 것은 화면이 곧바로 알아야 할 변화이다. 알림의 시각은 Incident가 열린 시각과
	 * 같아야 한다. 두 값이 다르면 나중에 알림과 기록을 나란히 놓고 볼 때 무엇이 먼저인지 말할 수 없다.
	 */
	@Test
	void announcesTheChangeWhenAnIncidentIsOpened() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2));
	}

	/** 연속이 아직 기준에 닿지 않아 아무것도 열리지 않았으면 알릴 변화도 없다. */
	@Test
	void saysNothingWhenNoIncidentHasOpenedYet() {
		detect(errorRateWarning(WINDOW_1));

		assertThat(this.announcedChanges).isEmpty();
	}

	/**
	 * 창을 판정하는 것과 Incident가 달라지는 것은 다른 일이다. 이상이 이어져도 이미 열린 기록에 바뀔 것이
	 * 없으면 알리지 않는다. 창마다 알리면 화면이 아무것도 달라지지 않은 상태를 반복해서 다시 읽는다.
	 */
	@Test
	void saysNothingWhileTheAbnormalWindowsChangeNothing() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		detect(errorRateWarning(WINDOW_3));
		detect(errorRateWarning(WINDOW_4));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2));
	}

	/** 이미 열린 장애가 더 나빠진 것은 화면이 다시 읽어야 할 변화이다. */
	@Test
	void announcesTheChangeWhenTheSeverityIsRaised() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(errorRateCritical(WINDOW_3));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2),
				new IncidentChangedEvent(openedId(), WINDOW_3));
	}

	/** 심각도가 오르지 않은 창은 Incident를 건드리지 않으므로 알림도 없다. */
	@Test
	void saysNothingWhenTheSeverityDoesNotGoUp() {
		detect(errorRateCritical(WINDOW_1));
		detect(errorRateCritical(WINDOW_2));

		detect(errorRateWarning(WINDOW_3));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2));
	}

	/** 끝난 것도 알려야 한다. 처리 중인 목록에서 사라지는 일이라 화면이 그대로 두면 끝난 장애가 남는다. */
	@Test
	void announcesTheChangeWhenAnIncidentIsResolved() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));

		detect(healthy(WINDOW_3));
		detect(healthy(WINDOW_4));
		detect(healthy(WINDOW_5));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2),
				new IncidentChangedEvent(openedId(), WINDOW_5));
	}

	/**
	 * 끝난 Incident는 판정이 다시 잠글 대상이 아니므로 회복이 이어져도 같은 기록을 두 번 알리지 않는다.
	 * 끝났다는 알림이 반복되면 화면은 그때마다 아무 변화도 없는 조회를 다시 한다.
	 */
	@Test
	void saysNothingWhenTheHealthyWindowsContinueAfterResolving() {
		detect(errorRateWarning(WINDOW_1));
		detect(errorRateWarning(WINDOW_2));
		detect(healthy(WINDOW_3));
		detect(healthy(WINDOW_4));
		detect(healthy(WINDOW_5));

		detect(healthy(WINDOW_6));
		detect(healthy(WINDOW_7));

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(openedId(), WINDOW_2),
				new IncidentChangedEvent(openedId(), WINDOW_5));
	}

	/**
	 * 판정할 창을 Snapshot으로 직접 정한다. 기준 시각은 그 창이 막 닫히고 다음 Bucket이 채워지는
	 * 중인 시각으로 두어, 판정이 그 Bucket을 보지 않는다는 전제를 모든 검증이 함께 쓴다.
	 */
	private void detect(MetricSnapshot snapshot) {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(snapshot);
		this.detector.detect(SERIES, snapshot.windowEnd().plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET));
	}

	private void acknowledge(Instant acknowledgedAt) {
		Incident incident = incident(IncidentType.ERROR_RATE).orElseThrow();
		incident.acknowledge(acknowledgedAt);
		this.incidentRepository.saveAndFlush(incident);
	}

	/** 알림이 가리키는 Incident가 실제로 저장된 그 기록인지 확인하기 위한 ID이다. */
	private Long openedId() {
		return incident(IncidentType.ERROR_RATE).orElseThrow().getId();
	}

	private DetectionState state(IncidentType type) {
		return this.detectionStateRepository.findAll()
			.stream()
			.filter((state) -> state.getType() == type)
			.findFirst()
			.orElseThrow(() -> new AssertionError("Detection State가 없다: type=" + type));
	}

	/** 같은 종류의 기록이 여러 개면 가장 최근에 열린 것을 본다. 끝난 이력은 그 뒤에 남는다. */
	private Optional<Incident> incident(IncidentType type) {
		return this.incidentRepository.findAll()
			.stream()
			.filter((incident) -> incident.getType() == type)
			.max(Comparator.comparing(Incident::getId));
	}

	private static MetricSnapshot errorRateWarning(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, WARNING_ERROR_COUNT, HEALTHY_LATENCY_MS);
	}

	private static MetricSnapshot errorRateCritical(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, CRITICAL_ERROR_COUNT, HEALTHY_LATENCY_MS);
	}

	private static MetricSnapshot latencyCritical(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, 0, CRITICAL_LATENCY_MS);
	}

	private static MetricSnapshot bothCritical(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, CRITICAL_ERROR_COUNT, CRITICAL_LATENCY_MS);
	}

	/** 두 종류 모두 회복 기준 아래인 창이다. */
	private static MetricSnapshot healthy(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, 0, HEALTHY_LATENCY_MS);
	}

	/** 두 종류 모두 이상 기준과 회복 기준 사이인 창이다. */
	private static MetricSnapshot neutral(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, NEUTRAL_ERROR_COUNT, NEUTRAL_LATENCY_MS);
	}

	/** 요청이 너무 적어 두 종류 모두 판정할 수 없는 창이다. */
	private static MetricSnapshot lowTraffic(Instant windowEnd) {
		return snapshot(windowEnd, TOO_FEW_REQUESTS, NEUTRAL_ERROR_COUNT, HEALTHY_LATENCY_MS);
	}

	/** 요청은 충분하지만 표본이 없어 P95를 구하지 못한 창이다. 오류율은 판정할 수 있다. */
	private static MetricSnapshot missingLatency(Instant windowEnd) {
		return snapshot(windowEnd, ENOUGH_REQUESTS, 0, null);
	}

	private static MetricSnapshot snapshot(Instant windowEnd, long requestCount, long errorCount, Long p95LatencyMs) {
		double errorRate = (requestCount == 0) ? 0.0 : (double) errorCount / requestCount * 100.0;
		Instant windowStart = windowEnd
			.minusSeconds((long) MetricWindow.BUCKET_SECONDS * MetricWindow.WINDOW_BUCKETS);
		return new MetricSnapshot(SERVICE, METHOD, ENDPOINT, windowStart, windowEnd, requestCount, errorCount,
				errorRate, p95LatencyMs);
	}

}
