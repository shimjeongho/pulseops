package com.pulseops.analysis;

import com.pulseops.github.GitHubCodeContext;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 진짜 Claude 대신 세워 두는 Client이다.
 * <p>
 * 저장 규칙과 요청 흐름을 확인하는 테스트가 HTTP를 흉내 낼 필요가 없게 한다. 몇 번 불렸는지 세어 두는
 * 것이 중요하다. 같은 Incident를 다시 요청했을 때 요금이 붙지 않는다는 약속은 "다시 부르지 않았다"로만
 * 확인할 수 있고, 결과가 같은 것만으로는 확인되지 않는다.
 * <p>
 * 호출 시점에 Transaction이 열려 있었는지도 기록한다. 외부 API를 기다리는 동안 DB 연결과 잠금을 쥐고
 * 있지 않다는 것은 코드를 읽어 짐작할 것이 아니라 확인할 사실이다.
 * <p>
 * 코드 맥락까지 보는 호출도 같은 횟수를 센다. 두 종류의 분석을 나눠 세면 "코드 기반 분석을 다시
 * 요청해도 요금이 붙지 않는다"와 "기본 분석은 건드리지 않았다"를 한 번에 확인할 수 없다. 대신 받은
 * 맥락을 따로 기록해 두어, 어느 호출이 무엇을 보고 만들어졌는지는 값으로 구분한다.
 * <p>
 * 이 Class는 테스트 전용이며 Production 경로에는 없다. {@code application.properties}에 가짜 mode를
 * 만들지 않는 이유가 그것이다. 그런 설정이 있으면 실수로 켠 운영 환경이 지어낸 분석을 저장한다.
 */
class FakeIncidentAnalysisClient implements IncidentAnalysisClient {

	/** 경쟁을 만들 때 상대를 기다리는 최대 시간이다. 이 시간을 넘기면 테스트가 매달리지 않고 실패한다. */
	private static final int RENDEZVOUS_TIMEOUT_SECONDS = 10;

	private final AtomicInteger callCount = new AtomicInteger();

	private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();

	private final List<IncidentAnalysisFacts> receivedFacts = new CopyOnWriteArrayList<>();

	/** 코드 맥락까지 보는 호출이 실제로 무엇을 받았는지이다. 기본 분석 호출에서는 늘지 않는다. */
	private final List<GitHubCodeContext> receivedCodeContexts = new CopyOnWriteArrayList<>();

	private volatile Function<IncidentAnalysisFacts, IncidentAnalysisResult> behavior = FakeIncidentAnalysisClient::defaultResult;

	/** 두 요청을 같은 지점에서 만나게 해 경쟁을 만든다. 필요 없을 때는 비어 있다. */
	private volatile CyclicBarrier rendezvous;

	@Override
	public IncidentAnalysisResult analyze(IncidentAnalysisFacts facts) {
		return record(facts);
	}

	@Override
	public IncidentAnalysisResult analyzeWithCode(IncidentAnalysisFacts facts, GitHubCodeContext code) {
		this.receivedCodeContexts.add(code);
		return record(facts);
	}

	private IncidentAnalysisResult record(IncidentAnalysisFacts facts) {
		this.callCount.incrementAndGet();
		this.receivedFacts.add(facts);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			this.calledInsideTransaction.set(true);
		}
		awaitRendezvous();
		return this.behavior.apply(facts);
	}

	void reset() {
		this.callCount.set(0);
		this.calledInsideTransaction.set(false);
		this.receivedFacts.clear();
		this.receivedCodeContexts.clear();
		this.behavior = FakeIncidentAnalysisClient::defaultResult;
		this.rendezvous = null;
	}

	void succeedWith(Function<IncidentAnalysisFacts, IncidentAnalysisResult> behavior) {
		this.behavior = behavior;
	}

	void failWith(RuntimeException failure) {
		this.behavior = (facts) -> {
			throw failure;
		};
	}

	/** 이 수만큼의 요청이 모두 도착할 때까지 아무도 결과를 돌려주지 않게 한다. */
	void rendezvousOf(int parties) {
		this.rendezvous = new CyclicBarrier(parties);
	}

	int callCount() {
		return this.callCount.get();
	}

	boolean calledInsideTransaction() {
		return this.calledInsideTransaction.get();
	}

	List<IncidentAnalysisFacts> receivedFacts() {
		return List.copyOf(this.receivedFacts);
	}

	List<GitHubCodeContext> receivedCodeContexts() {
		return List.copyOf(this.receivedCodeContexts);
	}

	private void awaitRendezvous() {
		CyclicBarrier barrier = this.rendezvous;
		if (barrier == null) {
			return;
		}
		try {
			barrier.await(RENDEZVOUS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("경쟁을 만드는 도중 중단됐다", ex);
		}
		catch (BrokenBarrierException | TimeoutException ex) {
			throw new IllegalStateException("경쟁을 만들 상대가 도착하지 않았다", ex);
		}
	}

	/**
	 * 어느 Incident를 받았는지 결과에서도 드러나게 한다. 두 Incident의 분석이 섞였을 때 값만 보고
	 * 알아챌 수 있어야 한다.
	 */
	private static IncidentAnalysisResult defaultResult(IncidentAnalysisFacts facts) {
		return new IncidentAnalysisResult("%s %s의 오류가 늘었다.".formatted(facts.method(), facts.endpoint()),
				"의존 서비스의 실패가 의심된다.", AnalysisConfidence.MEDIUM,
				List.of("trigger window의 오류 수가 %d였다.".formatted(facts.triggerErrorCount())),
				List.of("해당 endpoint의 서버 로그를 확인한다."), List.of("application log가 주어지지 않았다."),
				"claude-test-model", IncidentAnalysisPrompt.VERSION, IncidentAnalysisPrompt.factsJson(facts));
	}

}
