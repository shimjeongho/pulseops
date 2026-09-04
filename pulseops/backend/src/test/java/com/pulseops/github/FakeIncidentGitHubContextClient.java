package com.pulseops.github;

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
 * 진짜 GitHub 대신 세워 두는 Client이다.
 * <p>
 * 저장 규칙과 요청 흐름을 확인하는 테스트가 HTTP를 흉내 낼 필요가 없게 한다. 몇 번 불렸는지 세어 두는
 * 것이 중요하다. 같은 Incident를 다시 요청했을 때 GitHub 한도가 깎이지 않는다는 약속은 "다시 부르지
 * 않았다"로만 확인할 수 있고, 결과가 같은 것만으로는 확인되지 않는다.
 * <p>
 * 호출 시점에 Transaction이 열려 있었는지도 기록한다. 외부 API를 기다리는 동안 DB 연결과 잠금을 쥐고
 * 있지 않다는 것은 코드를 읽어 짐작할 것이 아니라 확인할 사실이다.
 * <p>
 * 이 Class는 테스트 전용이며 Production 경로에는 없다. {@code application.properties}에 가짜 mode를
 * 만들지 않는 이유가 그것이다. 그런 설정이 있으면 실수로 켠 운영 환경이 지어낸 코드 맥락을 저장한다.
 */
public class FakeIncidentGitHubContextClient implements IncidentGitHubContextClient {

	static final String REPOSITORY = "pulseops-test/pulseops";

	static final String REF = "main";

	static final String SOURCE_ROOT = "demo-shop/backend/src/main/java";

	/** 경쟁을 만들 때 상대를 기다리는 최대 시간이다. 이 시간을 넘기면 테스트가 매달리지 않고 실패한다. */
	private static final int RENDEZVOUS_TIMEOUT_SECONDS = 10;

	private final AtomicInteger callCount = new AtomicInteger();

	private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();

	private final List<IncidentGitHubContextQuery> receivedQueries = new CopyOnWriteArrayList<>();

	private volatile Function<IncidentGitHubContextQuery, GitHubCodeContext> behavior = FakeIncidentGitHubContextClient::defaultContext;

	/** 두 요청을 같은 지점에서 만나게 해 경쟁을 만든다. 필요 없을 때는 비어 있다. */
	private volatile CyclicBarrier rendezvous;

	@Override
	public GitHubCodeContext collect(IncidentGitHubContextQuery query) {
		this.callCount.incrementAndGet();
		this.receivedQueries.add(query);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			this.calledInsideTransaction.set(true);
		}
		awaitRendezvous();
		return this.behavior.apply(query);
	}

	public void reset() {
		this.callCount.set(0);
		this.calledInsideTransaction.set(false);
		this.receivedQueries.clear();
		this.behavior = FakeIncidentGitHubContextClient::defaultContext;
		this.rendezvous = null;
	}

	public void succeedWith(Function<IncidentGitHubContextQuery, GitHubCodeContext> behavior) {
		this.behavior = behavior;
	}

	/** 관련 source를 찾지 못한 정상적인 결과이다. 실패가 아니다. */
	public void findNothing() {
		this.behavior = (query) -> new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, List.of(), List.of());
	}

	public void failWith(RuntimeException failure) {
		this.behavior = (query) -> {
			throw failure;
		};
	}

	/** 이 수만큼의 요청이 모두 도착할 때까지 아무도 결과를 돌려주지 않게 한다. */
	public void rendezvousOf(int parties) {
		this.rendezvous = new CyclicBarrier(parties);
	}

	public int callCount() {
		return this.callCount.get();
	}

	public boolean calledInsideTransaction() {
		return this.calledInsideTransaction.get();
	}

	public List<IncidentGitHubContextQuery> receivedQueries() {
		return List.copyOf(this.receivedQueries);
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
	 * 어느 endpoint를 받았는지 결과에서도 드러나게 한다. 두 Incident의 맥락이 섞였을 때 값만 보고
	 * 알아챌 수 있어야 한다.
	 */
	private static GitHubCodeContext defaultContext(IncidentGitHubContextQuery query) {
		return new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT,
				List.of(new GitHubSourceFile(SOURCE_ROOT + "/com/pulseops/demoshop/order/OrderController.java",
						"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
						"@PostMapping\npublic OrderResponse create() { // %s".formatted(query.endpoint()), true)),
				List.of(new GitHubCommit("0f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6", "주문 생성 검증을 고쳤다",
						query.openedAt().minusSeconds(3600),
						List.of(SOURCE_ROOT + "/com/pulseops/demoshop/order/OrderController.java"))));
	}

}
