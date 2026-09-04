package com.pulseops.github;

import com.pulseops.github.dto.IncidentGitHubContextResponse;
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
 * Incident 하나의 GitHub 코드 맥락을 모으고 읽는다.
 * <p>
 * 사람이 요청할 때만 모은다. Incident가 열리는 순간에도, Scheduler에서도, SSE 알림을 보낼 때도 GitHub을
 * 부르지 않는다. Incident 탐지는 Kafka Event가 들어올 때 결정론적 규칙으로 일어나며 그 흐름에 외부 API가
 * 끼어들면 GitHub이 느리거나 한도를 넘긴 동안 Incident 처리가 함께 멈춘다. 게다가 code search는 분당
 * 열 번뿐이라, 열리는 Incident마다 부르면 사람이 정작 필요할 때 쓸 한도가 남아 있지 않다.
 * <p>
 * Incident를 바꾸지 않는다. 어떤 코드를 찾았든 severity도 status도 판정 상태도 그대로다. GitHub은
 * 탐지기가 아니며, 여기서 Incident를 한 글자라도 고치면 그 순간부터 외부 저장소의 내용이 판정에 참여한다.
 * <p>
 * Incident당 맥락은 하나다. 같은 Incident에 다시 요청하면 저장된 것을 그대로 돌려주고 GitHub을 부르지
 * 않는다. 맥락은 그 시점을 기준으로 모은 것이라 다시 모을 이유가 없고, 요청할 때마다 다시 부르면 화면을
 * 새로 고치는 것만으로 한도가 깎인다.
 * <p>
 * 외부 호출을 Transaction 밖에서 한다. 아래 {@link #collect} 어디에도 Transaction 경계가 없고 저장소
 * 호출이 각자 자기 경계를 갖는다. 맥락 하나에 GitHub 호출이 여러 번 나가므로 최악의 경우 수십 초가
 * 걸리는데, 그동안 DB 연결과 잠금을 쥐고 있으면 수집 요청 몇 개가 연결 풀을 비워 Incident 조회와 확인
 * 처리가 함께 막힌다. 그래서 읽고, 경계를 닫고, 부르고, 짧은 경계 안에서 한 행을 넣는다.
 */
@Service
public class IncidentGitHubContextService {

	private static final Logger logger = LoggerFactory.getLogger(IncidentGitHubContextService.class);

	private final IncidentRepository incidentRepository;

	private final IncidentGitHubContextRepository incidentGitHubContextRepository;

	/**
	 * 기능이 꺼져 있으면 비어 있다. 껍데기 구현을 두지 않는 것은, 그러면 "지금 GitHub을 부를 수 있는가"가
	 * 구성이 아니라 어느 method 안의 분기가 되기 때문이다.
	 */
	private final Optional<IncidentGitHubContextClient> incidentGitHubContextClient;

	public IncidentGitHubContextService(IncidentRepository incidentRepository,
			IncidentGitHubContextRepository incidentGitHubContextRepository,
			Optional<IncidentGitHubContextClient> incidentGitHubContextClient) {
		this.incidentRepository = incidentRepository;
		this.incidentGitHubContextRepository = incidentGitHubContextRepository;
		this.incidentGitHubContextClient = incidentGitHubContextClient;
	}

	/**
	 * 저장된 맥락을 읽는다.
	 * <p>
	 * 기능이 꺼져 있어도 읽을 수 있다. 설정이 정하는 것은 새로 모을 수 있는지이지, 이미 모아 저장된
	 * 기록을 보여 줄 수 있는지가 아니다. token을 내린 뒤 지난 맥락을 읽지 못하게 되면 그 기록은 DB에만
	 * 남고 아무도 볼 수 없는 값이 되며, 그것을 근거로 만든 분석도 무엇을 봤는지 설명할 수 없게 된다.
	 * <p>
	 * 없을 때 두 가지 404를 나눈다. 맥락부터 찾고 없을 때만 Incident의 존재를 확인하므로, 대부분을
	 * 차지하는 "있는 맥락 읽기"에는 조회가 하나뿐이다.
	 */
	public IncidentGitHubContextResponse context(Long incidentId) {
		return this.incidentGitHubContextRepository.findByIncidentId(incidentId)
			.map(IncidentGitHubContextResponse::from)
			.orElseThrow(() -> missingContext(incidentId));
	}

	/**
	 * 맥락을 모은다. 이미 있으면 모으지 않고 그것을 돌려준다.
	 * <p>
	 * 이미 있는지부터 본다. 이 확인이 GitHub을 부르지 않는 유일한 이유이며, 화면에서 버튼을 다시 눌러도
	 * 한도가 깎이지 않는 것도 여기 덕분이다.
	 * <p>
	 * 그다음 Incident를 읽는다. 없는 Incident를 가리킨 요청은 기능이 켜져 있든 꺼져 있든 잘못된 요청이라
	 * 기능 상태보다 먼저 답한다. 상태로 거르지 않으므로 이미 해결된 Incident도 수집할 수 있다. 지난
	 * 장애를 사후에 들여다보는 것이 이 기능의 주된 쓰임이다.
	 * <p>
	 * 관련 코드를 찾지 못해도 저장한다. 그것은 실패가 아니라 "찾아봤지만 없었다"는 결과이고, 그 사실이
	 * 남아야 다시 누를 때마다 같은 검색을 반복해 한도를 깎는 일이 없다. 다만 그 맥락으로 코드 기반
	 * 분석을 만들지는 않는다.
	 * <p>
	 * 저장할 때 제약 위반을 받는다. 같은 Incident에 두 요청이 나란히 들어오면 둘 다 저장된 맥락이 없다고
	 * 보고 각자 GitHub을 부를 수 있다. 그때 나중에 저장하는 쪽이 UNIQUE에 걸리며, 그 결과를 버리고 먼저
	 * 저장된 것을 돌려준다. 남는 행은 언제나 하나다. 그 경쟁에서 GitHub 호출이 두 번 나가는 것은 Phase 9의
	 * 분석과 같은 이유로 남겨 둔 부채이며, 막으려면 외부 API를 기다리는 동안 잠금을 쥐어야 한다.
	 * <p>
	 * 실패하면 아무것도 저장하지 않는다. 실패한 시도를 기록으로 남기지 않으므로 다음 요청은 처음부터
	 * 다시 시도할 수 있다.
	 */
	public IncidentGitHubContextResponse collect(Long incidentId) {
		Optional<IncidentGitHubContext> existing = this.incidentGitHubContextRepository.findByIncidentId(incidentId);
		if (existing.isPresent()) {
			logger.info("이미 있는 GitHub 코드 맥락을 돌려주었다. incidentId={} created=false", incidentId);
			return IncidentGitHubContextResponse.from(existing.get());
		}

		Incident incident = this.incidentRepository.findById(incidentId)
			.orElseThrow(() -> new IncidentNotFoundException(incidentId));
		IncidentGitHubContextClient client = this.incidentGitHubContextClient
			.orElseThrow(() -> new IncidentGitHubContextUnavailableException("feature_disabled", null));

		long startedAt = System.nanoTime();
		GitHubCodeContext context = client.collect(IncidentGitHubContextQuery.from(incident));
		logger.info("GitHub 코드 맥락을 수집했다. incidentId={} repository={} matchedFiles={} commits={} elapsedMs={} created=true",
				incidentId, context.repository(), context.files().size(), context.commits().size(),
				elapsedMillis(startedAt));
		return save(incidentId, context);
	}

	/**
	 * 코드 기반 분석이 근거로 쓸 맥락을 꺼낸다.
	 * <p>
	 * 이 method가 있어서 분석 쪽은 GitHub을 알 필요가 없다. 분석을 몇 번 만들든 근거는 이미 저장된 이
	 * 기록이며, 새로운 외부 호출은 한 번도 일어나지 않는다.
	 * <p>
	 * 관련 source가 없는 맥락은 거절한다. 그 맥락으로 만든 분석은 이름만 코드 기반일 뿐 기존 분석과 같은
	 * 근거로 만든 두 번째 분석이 되고, 두 분석의 차이를 사람에게 설명할 수 없게 된다.
	 */
	public IncidentGitHubContext requireCodeContext(Long incidentId) {
		IncidentGitHubContext context = this.incidentGitHubContextRepository.findByIncidentId(incidentId)
			.orElseThrow(() -> new IncidentGitHubContextNotFoundException(incidentId));
		if (context.toCodeContext().hasNoSource()) {
			throw new IncidentGitHubContextEmptyException(incidentId);
		}
		return context;
	}

	/**
	 * 저장 시각을 마이크로초까지로 맞춘다.
	 * <p>
	 * {@code captured_at}이 DATETIME(6)이라 DB는 그 아래를 담지 못한다. 시계에서 읽은 값을 그대로 쓰면
	 * 방금 만든 맥락의 응답에는 나노초까지 담긴 시각이 나가고 다시 읽은 응답에는 반올림된 시각이 나가,
	 * 하나뿐인 맥락이 두 개의 수집 시각을 갖는다.
	 */
	private IncidentGitHubContextResponse save(Long incidentId, GitHubCodeContext context) {
		IncidentGitHubContext stored = IncidentGitHubContext.of(incidentId, context,
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		try {
			return IncidentGitHubContextResponse.from(this.incidentGitHubContextRepository.saveAndFlush(stored));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 Incident를 다른 요청이 먼저 저장했다면 그것이 정답이다. 다른 제약이 깨진 것이라면 그
			// Incident의 맥락은 여전히 없으므로 원래 실패를 그대로 올려 보낸다.
			logger.info("다른 요청이 먼저 저장한 GitHub 코드 맥락을 돌려주었다. incidentId={}", incidentId);
			return this.incidentGitHubContextRepository.findByIncidentId(incidentId)
				.map(IncidentGitHubContextResponse::from)
				.orElseThrow(() -> ex);
		}
	}

	/**
	 * 맥락이 없는 이유를 나눈다. Incident 자체가 없으면 그것이 먼저 알려야 할 사실이고, Incident는 있는데
	 * 맥락만 없는 것은 아직 아무도 수집하지 않았다는 정상적인 상태이다. 둘을 같은 code로 답하면 화면이
	 * 수집 버튼을 보여 줘야 할 자리에 Incident를 찾을 수 없다는 문구를 그린다.
	 */
	private RuntimeException missingContext(Long incidentId) {
		if (!this.incidentRepository.existsById(incidentId)) {
			return new IncidentNotFoundException(incidentId);
		}
		return new IncidentGitHubContextNotFoundException(incidentId);
	}

	private static long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

}
