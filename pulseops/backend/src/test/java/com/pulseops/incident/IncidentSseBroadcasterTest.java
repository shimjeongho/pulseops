package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.pulseops.incident.dto.IncidentStreamConnectedResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 열려 있는 연결들이 어떻게 늘고 줄며, 하나가 망가졌을 때 나머지가 어떻게 되는지를 검증한다.
 * <p>
 * 실제 HTTP를 세우지 않는다. 이 Class의 책임은 구독 목록을 옳게 관리하는 것이고, 그 판단은 연결이
 * 진짜 Socket인지와 상관이 없다. 대신 연결을 흉내 내면 실제로는 만들기 어려운 전송 실패와 끊김 통보를
 * 원하는 순간에 일으킬 수 있다.
 * <p>
 * 나가는 줄의 실제 모양은 여기서 보지 않는다. 이름과 본문이 SSE 규격의 어떤 글자로 바뀌는지는 Spring
 * MVC와 Jackson이 정하는 일이라, 흉내 낸 연결 위에서 확인하면 통과해도 Client가 받을 것을 보증하지
 * 않는다. 그 계약은 {@link IncidentStreamApiIntegrationTest}가 실제 응답으로 고정한다. 여기서는 어떤
 * 이름으로 어떤 값을 넘겼는가까지만 본다.
 */
class IncidentSseBroadcasterTest {

	private static final IncidentChangedEvent CHANGE = new IncidentChangedEvent(7L,
			Instant.parse("2026-09-01T06:00:00Z"));

	private final IncidentSseBroadcaster broadcaster = new IncidentSseBroadcaster();

	/**
	 * 200과 Content-Type만으로는 연결이 이어졌는지 알 수 없다. 첫 Event가 실제로 도착해야 Client가
	 * 기다려도 되는 상태인지 판단할 수 있다.
	 * <p>
	 * 그 Event에 Incident에 관한 값은 담기지 않는다. 연결 직후 무엇을 그릴지는 REST 조회가 정하며,
	 * 여기에 목록을 실어 보내면 같은 목록을 만드는 자리가 둘이 된다. 본문의 Type이 그것을 고정한다.
	 */
	@Test
	void tellsANewSubscriberThatTheConnectionIsOpen() throws IOException {
		SseEmitter emitter = mock(SseEmitter.class);

		this.broadcaster.subscribe(emitter);

		assertThat(sentTo(emitter)).singleElement().satisfies((event) -> {
			assertThat(nameOf(event)).isEqualTo("connected");
			assertThat(payloadOf(event)).isInstanceOf(IncidentStreamConnectedResponse.class);
		});
		assertThat(this.broadcaster.subscriberCount()).isEqualTo(1);
	}

	/** 보고 있는 화면이 여럿이면 전부가 같은 변화를 알아야 한다. 하나만 알면 나머지는 낡은 상태를 그린다. */
	@Test
	void announcesTheChangeToEverySubscriber() throws IOException {
		SseEmitter first = mock(SseEmitter.class);
		SseEmitter second = mock(SseEmitter.class);
		this.broadcaster.subscribe(first);
		this.broadcaster.subscribe(second);

		this.broadcaster.broadcast(CHANGE);

		assertThat(announcementTo(first)).isEqualTo(CHANGE);
		assertThat(announcementTo(second)).isEqualTo(CHANGE);
	}

	/** 알림에는 어느 Incident가 언제 바뀌었는지만 담는다. 상태를 실어 보내면 화면이 그것을 믿기 시작한다. */
	@Test
	void announcesOnlyWhichIncidentChangedAndWhen() throws IOException {
		SseEmitter emitter = mock(SseEmitter.class);
		this.broadcaster.subscribe(emitter);

		this.broadcaster.broadcast(CHANGE);

		assertThat(nameOf(sentTo(emitter).get(1))).isEqualTo("incident-changed");
		assertThat(announcementTo(emitter)).isEqualTo(CHANGE);
	}

	/**
	 * 이미 끊어진 Client 하나 때문에 살아 있는 화면들이 변화를 놓치면 안 된다. 알림은 Client마다
	 * 독립적인 일이다.
	 */
	@Test
	void keepsAnnouncingToTheOthersWhenOneSubscriberFails() throws IOException {
		SseEmitter broken = mock(SseEmitter.class);
		SseEmitter healthy = mock(SseEmitter.class);
		this.broadcaster.subscribe(broken);
		this.broadcaster.subscribe(healthy);
		willThrow(new IOException("연결이 끊어졌다")).given(broken).send(any(SseEventBuilder.class));

		this.broadcaster.broadcast(CHANGE);

		assertThat(announcementTo(healthy)).isEqualTo(CHANGE);
		assertThat(this.broadcaster.subscriberCount()).isEqualTo(1);
		then(broken).should().completeWithError(any(IOException.class));
	}

	/** 쓸 수 없게 된 연결에 다시 시도하지 않는다. 남겨 두면 이후의 모든 알림이 그 자리에서 실패한다. */
	@Test
	void neverRetriesASubscriberThatAlreadyFailed() throws IOException {
		SseEmitter broken = mock(SseEmitter.class);
		this.broadcaster.subscribe(broken);
		willThrow(new IOException("연결이 끊어졌다")).given(broken).send(any(SseEventBuilder.class));
		this.broadcaster.broadcast(CHANGE);

		this.broadcaster.broadcast(CHANGE);

		assertThat(this.broadcaster.subscriberCount()).isZero();
		then(broken).should(times(2)).send(any(SseEventBuilder.class));
	}

	/**
	 * 첫 Event조차 보내지 못한 연결을 목록에 남기면 안 된다. 아직 응답이 시작되지 않아 끊어졌다는
	 * 통보가 오지 않을 수 있고, 그러면 아무에게도 닿지 않는 자리가 영원히 남는다.
	 */
	@Test
	void dropsTheSubscriptionWhenTheConnectionCannotBeOpened() throws IOException {
		SseEmitter emitter = mock(SseEmitter.class);
		willThrow(new IOException("연결이 끊어졌다")).given(emitter).send(any(SseEventBuilder.class));

		this.broadcaster.subscribe(emitter);

		assertThat(this.broadcaster.subscriberCount()).isZero();
		then(emitter).should().completeWithError(any(IOException.class));
	}

	/** 정상적으로 끝난 연결의 자리는 사라져야 한다. 남으면 화면을 열고 닫을 때마다 목록이 자란다. */
	@Test
	void removesTheSubscriptionWhenTheConnectionCompletes() {
		SseEmitter emitter = mock(SseEmitter.class);
		this.broadcaster.subscribe(emitter);
		ArgumentCaptor<Runnable> completion = ArgumentCaptor.captor();
		then(emitter).should().onCompletion(completion.capture());

		completion.getValue().run();

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	/** 시간이 다 되어 서버가 끊은 연결도 마찬가지다. 이 길로 끝나는 연결이 가장 흔하다. */
	@Test
	void removesTheSubscriptionWhenTheConnectionTimesOut() {
		SseEmitter emitter = mock(SseEmitter.class);
		this.broadcaster.subscribe(emitter);
		ArgumentCaptor<Runnable> timeout = ArgumentCaptor.captor();
		then(emitter).should().onTimeout(timeout.capture());

		timeout.getValue().run();

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	/** 오류로 끝난 연결도 마찬가지다. 세 가지 길 중 하나만 빠뜨려도 그 길로 끝난 연결이 쌓인다. */
	@Test
	void removesTheSubscriptionWhenTheConnectionFails() {
		SseEmitter emitter = mock(SseEmitter.class);
		this.broadcaster.subscribe(emitter);
		ArgumentCaptor<Consumer<Throwable>> failure = ArgumentCaptor.captor();
		then(emitter).should().onError(failure.capture());

		failure.getValue().accept(new IOException("연결이 끊어졌다"));

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	/** 끊긴 통보가 두 번 와도 결과는 같아야 한다. Container가 언제 몇 번 알려 줄지는 정해져 있지 않다. */
	@Test
	void toleratesTheSameConnectionEndingTwice() {
		SseEmitter emitter = mock(SseEmitter.class);
		this.broadcaster.subscribe(emitter);
		ArgumentCaptor<Runnable> completion = ArgumentCaptor.captor();
		then(emitter).should().onCompletion(completion.capture());

		completion.getValue().run();
		completion.getValue().run();

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	/**
	 * 보고 있는 사람이 없는 것은 정상이다. Incident는 아무도 화면을 열지 않은 새벽에도 열리고 닫히며,
	 * 그때 알림이 실패하면 그 실패가 판정 쪽으로 번진다.
	 */
	@Test
	void staysQuietWhenNobodyIsWatching() {
		assertThatNoException().isThrownBy(() -> this.broadcaster.broadcast(CHANGE));

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	/** 구독 직후의 연결 알림 다음에 오는 것이 변경 알림이다. */
	private static IncidentChangedEvent announcementTo(SseEmitter emitter) throws IOException {
		return (IncidentChangedEvent) payloadOf(sentTo(emitter).get(1));
	}

	private static List<SseEventBuilder> sentTo(SseEmitter emitter) throws IOException {
		ArgumentCaptor<SseEventBuilder> sent = ArgumentCaptor.captor();
		then(emitter).should(atLeastOnce()).send(sent.capture());
		return sent.getAllValues();
	}

	/**
	 * Builder는 이름을 SSE의 줄로 미리 바꾸어 문자열 조각으로 들고 있고 본문만 객체로 남긴다. 그래서
	 * 이름은 문자열 조각에서 읽고 본문은 문자열이 아닌 조각에서 읽는다.
	 */
	private static String nameOf(SseEventBuilder builder) {
		return builder.build()
			.stream()
			.map(DataWithMediaType::getData)
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter((part) -> part.startsWith("event:"))
			.map((part) -> part.substring("event:".length()).lines().findFirst().orElseThrow())
			.findFirst()
			.orElseThrow(() -> new AssertionError("이름이 없는 Event이다"));
	}

	private static Object payloadOf(SseEventBuilder builder) {
		return builder.build()
			.stream()
			.map(DataWithMediaType::getData)
			.filter((part) -> !(part instanceof String))
			.findFirst()
			.orElseThrow(() -> new AssertionError("본문이 없는 Event이다"));
	}

}
