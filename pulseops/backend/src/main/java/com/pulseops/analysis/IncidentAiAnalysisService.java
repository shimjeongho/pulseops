package com.pulseops.analysis;

import com.pulseops.analysis.dto.IncidentAiAnalysisResponse;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentNotFoundException;
import com.pulseops.incident.IncidentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Incident 하나의 AI 분석을 만들고 읽는다.
 * <p>
 * 분석은 사람이 요청할 때만 만들어진다. Incident가 열리는 순간 자동으로 부르지 않는다. Incident는
 * Kafka Event가 들어올 때 결정론적 규칙으로 열리며, 그 흐름에 외부 API가 끼어들면 Claude가 느리거나
 * 죽어 있는 동안 Incident 처리 자체가 함께 멈춘다. 게다가 열리는 Incident마다 요금이 붙는데 그중 사람이
 * 실제로 들여다보는 것은 일부다. 어떤 Incident에 분석이 필요한지 아는 것은 사람이다.
 * <p>
 * Incident를 바꾸지 않는다. 분석 결과가 무엇이든 severity도 status도 판정 상태도 그대로다. AI는
 * 탐지기가 아니며, 여기서 Incident를 한 글자라도 고치면 그 순간부터 AI가 판정에 참여하게 된다.
 * <p>
 * Incident당 성공한 분석은 하나다. 같은 Incident에 다시 요청하면 저장된 것을 그대로 돌려주고 Claude를
 * 부르지 않는다. 분석은 만들어진 시점의 관측을 보고 만든 것이라 다시 만들 이유가 없고, 요청할 때마다
 * 다시 부르면 화면을 새로 고치는 것만으로 요금이 붙는다.
 * <p>
 * 외부 호출을 Transaction 밖에서 한다. 아래 {@link #analyze} 어디에도 Transaction 경계가 없고 저장소
 * 호출이 각자 자기 경계를 갖는다. Claude 호출은 수십 초가 걸릴 수 있는데 그동안 DB 연결과 잠금을 쥐고
 * 있으면, 분석 요청 몇 개가 연결 풀을 비워 Incident 조회와 확인 처리가 함께 막힌다. 그래서 읽고, 경계를
 * 닫고, 부르고, 짧은 경계 안에서 한 행을 넣는다.
 * <p>
 * {@code TransactionTemplate}을 쓰지 않는다. {@code DailyIncidentReportService}가 그것을 쓰는 이유는 여러
 * 조회와 저장을 한 경계로 묶고 그 경계 밖에서 제약 위반을 처리해야 하기 때문인데, 여기서 쓰기는 저장
 * 한 번뿐이라 저장소가 스스로 여는 경계가 곧 필요한 경계의 전부이다.
 */
@Service
public class IncidentAiAnalysisService {

	private static final Logger logger = LoggerFactory.getLogger(IncidentAiAnalysisService.class);

	private final IncidentRepository incidentRepository;

	private final IncidentAiAnalysisRepository incidentAiAnalysisRepository;

	/**
	 * 기능이 꺼져 있으면 비어 있다. 껍데기 구현을 두지 않는 것은, 그러면 "지금 분석을 만들 수 있는가"가
	 * 구성이 아니라 어느 method 안의 분기가 되기 때문이다.
	 */
	private final Optional<IncidentAnalysisClient> incidentAnalysisClient;

	public IncidentAiAnalysisService(IncidentRepository incidentRepository,
			IncidentAiAnalysisRepository incidentAiAnalysisRepository,
			Optional<IncidentAnalysisClient> incidentAnalysisClient) {
		this.incidentRepository = incidentRepository;
		this.incidentAiAnalysisRepository = incidentAiAnalysisRepository;
		this.incidentAnalysisClient = incidentAnalysisClient;
	}

	/**
	 * 저장된 분석을 읽는다.
	 * <p>
	 * 기능이 꺼져 있어도 읽을 수 있다. 설정이 정하는 것은 새 요청을 보낼 수 있는지이지, 이미 만들어져
	 * 저장된 기록을 보여 줄 수 있는지가 아니다. credential을 내린 뒤 지난 분석을 읽지 못하게 되면 그
	 * 기록은 DB에만 남고 아무도 볼 수 없는 값이 된다.
	 * <p>
	 * 없을 때 두 가지 404를 나눈다. 분석부터 찾고 없을 때만 Incident의 존재를 확인하므로, 대부분을
	 * 차지하는 "있는 분석 읽기"에는 조회가 하나뿐이다. 순서를 뒤집으면 매번 Incident를 한 번 더 읽는다.
	 */
	public IncidentAiAnalysisResponse analysis(Long incidentId) {
		return this.incidentAiAnalysisRepository.findByIncidentId(incidentId)
			.map(IncidentAiAnalysisResponse::from)
			.orElseThrow(() -> missingAnalysis(incidentId));
	}

	/**
	 * 분석을 만든다. 이미 있으면 만들지 않고 그것을 돌려준다.
	 * <p>
	 * 이미 있는지부터 본다. 이 확인이 Claude를 부르지 않는 유일한 이유이며, 화면에서 버튼을 다시 눌러도
	 * 요금이 붙지 않는 것도 여기 덕분이다.
	 * <p>
	 * 그다음 Incident를 읽는다. 없는 Incident를 가리킨 요청은 기능이 켜져 있든 꺼져 있든 잘못된 요청이라
	 * 기능 상태보다 먼저 답한다. 상태로 거르지 않으므로 이미 해결된 Incident도 분석할 수 있다. 지난
	 * 장애를 사후에 들여다보는 것이 이 기능의 주된 쓰임이고, 무엇을 분석할 수 있는지는 Incident의 처리
	 * 단계가 정할 일이 아니다.
	 * <p>
	 * 저장할 때 제약 위반을 받는다. 같은 Incident에 두 요청이 나란히 들어오면 둘 다 저장된 분석이 없다고
	 * 보고 각자 Claude를 부를 수 있다. 그때 나중에 저장하는 쪽이 UNIQUE에 걸리며, 그 결과를 버리고 먼저
	 * 저장된 것을 돌려준다. 남는 행은 언제나 하나다.
	 * <p>
	 * 그 경쟁에서 Claude 호출이 두 번 나가는 것은 남겨 둔 부채다. 막으려면 아직 없는 행을 잠그거나 분산
	 * 잠금을 들여와야 하는데, 그것은 외부 API를 기다리는 동안 잠금을 쥐고 있겠다는 뜻이라 위에서 피하려던
	 * 문제를 그대로 다시 만든다. 사람이 버튼을 누르는 요청이라 그 경쟁 자체가 드물고, 대가는 정확성이
	 * 아니라 아주 가끔의 중복 요금이다.
	 * <p>
	 * 실패하면 아무것도 저장하지 않는다. 실패한 시도를 기록으로 남기지 않으므로 다음 요청은 처음부터
	 * 다시 시도할 수 있다. 실패를 저장해 두면 그 Incident는 다시 분석할 수 없거나, 실패 기록을 지우는
	 * 또 다른 경로가 필요해진다.
	 */
	public IncidentAiAnalysisResponse analyze(Long incidentId) {
		Optional<IncidentAiAnalysis> existing = this.incidentAiAnalysisRepository.findByIncidentId(incidentId);
		if (existing.isPresent()) {
			logger.info("이미 있는 AI 분석을 돌려주었다. incidentId={} created=false", incidentId);
			return IncidentAiAnalysisResponse.from(existing.get());
		}

		Incident incident = this.incidentRepository.findById(incidentId)
			.orElseThrow(() -> new IncidentNotFoundException(incidentId));
		IncidentAnalysisClient client = this.incidentAnalysisClient
			.orElseThrow(() -> new IncidentAnalysisUnavailableException("feature_disabled", null));

		long startedAt = System.nanoTime();
		IncidentAnalysisResult result = client.analyze(IncidentAnalysisFacts.from(incident));
		logger.info("AI 분석을 생성했다. incidentId={} model={} elapsedMs={} created=true", incidentId, result.model(),
				elapsedMillis(startedAt));
		return save(incidentId, result);
	}

	/**
	 * 저장 시각을 마이크로초까지로 맞춘다.
	 * <p>
	 * {@code generated_at}이 DATETIME(6)이라 DB는 그 아래를 담지 못한다. 시계에서 읽은 값을 그대로 쓰면
	 * 방금 만든 분석의 응답에는 나노초까지 담긴 시각이 나가고 다시 읽은 응답에는 반올림된 시각이 나가,
	 * 하나뿐인 분석이 두 개의 생성 시각을 갖는다. 저장되는 값과 돌려주는 값이 같아야 한다.
	 */
	private IncidentAiAnalysisResponse save(Long incidentId, IncidentAnalysisResult result) {
		IncidentAiAnalysis analysis = IncidentAiAnalysis.of(incidentId, result,
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		try {
			return IncidentAiAnalysisResponse.from(this.incidentAiAnalysisRepository.saveAndFlush(analysis));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 Incident를 다른 요청이 먼저 저장했다면 그것이 정답이다. 다른 제약이 깨진 것이라면 그
			// Incident의 분석은 여전히 없으므로 원래 실패를 그대로 올려 보낸다.
			logger.info("다른 요청이 먼저 저장한 AI 분석을 돌려주었다. incidentId={}", incidentId);
			return this.incidentAiAnalysisRepository.findByIncidentId(incidentId)
				.map(IncidentAiAnalysisResponse::from)
				.orElseThrow(() -> ex);
		}
	}

	/**
	 * 분석이 없는 이유를 나눈다. Incident 자체가 없으면 그것이 먼저 알려야 할 사실이고, Incident는 있는데
	 * 분석만 없는 것은 아직 아무도 요청하지 않았다는 정상적인 상태이다. 둘을 같은 code로 답하면 화면이
	 * 분석 버튼을 보여 줘야 할 자리에 Incident를 찾을 수 없다는 문구를 그린다.
	 */
	private RuntimeException missingAnalysis(Long incidentId) {
		if (!this.incidentRepository.existsById(incidentId)) {
			return new IncidentNotFoundException(incidentId);
		}
		return new IncidentAiAnalysisNotFoundException(incidentId);
	}

	private static long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

}
