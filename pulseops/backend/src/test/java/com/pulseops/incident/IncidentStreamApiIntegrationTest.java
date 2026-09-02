package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.pulseops.MySqlTestcontainersConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Client가 실제로 받는 Stream의 모양을 고정한다.
 * <p>
 * 여기서만 확인할 수 있는 것이 있다. Event의 이름과 본문이 SSE 규격의 어떤 글자로 바뀌는지, 본문이
 * 어떤 JSON이 되는지는 Spring MVC와 Jackson이 정하는 일이라, 그 둘을 실제로 지나야 Client가 받을 것을
 * 말할 수 있다. 구독 목록을 어떻게 관리하는가는 {@link IncidentSseBroadcasterTest}가 따로 본다.
 * <p>
 * 알림에 무엇이 실리는지가 이 Class의 가장 중요한 계약이다. 이 Stream은 상태를 나르는 통로가 아니라
 * 다시 읽으라는 신호이므로, 본문에 상태가 하나라도 섞이면 화면이 그것을 믿기 시작한다. 그래서 담긴
 * 항목의 이름을 통째로 고정한다.
 * <p>
 * 열어 둔 Stream은 반드시 닫는다. 구독 목록은 Application이 하나만 들고 있어, 닫지 않으면 다음 검증이
 * 세는 구독 수가 앞 검증이 남긴 것까지 포함한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentStreamApiIntegrationTest {

	private static final List<String> CHANGED_FIELDS = List.of("incidentId", "occurredAt");

	private static final IncidentChangedEvent CHANGE = new IncidentChangedEvent(7L,
			Instant.parse("2026-09-01T06:00:00Z"));

	private final List<MvcResult> openedStreams = new ArrayList<>();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IncidentSseBroadcaster broadcaster;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void closeOpenedStreams() {
		this.openedStreams.forEach((stream) -> stream.getRequest().getAsyncContext().complete());
	}

	/**
	 * 응답을 열어 둔 채로 돌려주어야 한다. 값을 담아 곧바로 끝나는 응답이면 Client는 변경을 기다릴
	 * 자리가 없어 다시 물어보는 것 말고 할 수 있는 일이 없다.
	 */
	@Test
	void opensAStreamOfServerSentEvents() throws Exception {
		MvcResult stream = openStream();

		assertThat(stream.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
		assertThat(stream.getRequest().isAsyncStarted()).isTrue();
		assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
	}

	/**
	 * 200과 Content-Type만으로는 연결이 이어졌는지 알 수 없다. 첫 Event가 실제로 도착해야 Client가
	 * 기다려도 되는 상태인지 판단할 수 있다.
	 */
	@Test
	void sendsAConnectedEventAsSoonAsTheStreamOpens() throws Exception {
		MvcResult stream = openStream();

		assertThat(eventNamesOf(stream)).containsExactly("connected");
		assertThat(payloadOf(stream, "connected").propertyNames()).containsExactly("connectedAt");
	}

	/**
	 * 알림에는 어느 Incident가 언제 바뀌었는지만 담는다. 상태나 심각도가 하나라도 섞이면 화면이 그것을
	 * 최신 값으로 쓰기 시작하고, 알림을 놓친 화면과 받은 화면이 서로 다른 것을 믿게 된다.
	 */
	@Test
	void announcesOnlyWhichIncidentChangedAndWhen() throws Exception {
		MvcResult stream = openStream();

		this.broadcaster.broadcast(CHANGE);

		assertThat(eventNamesOf(stream)).containsExactly("connected", "incident-changed");
		JsonNode announcement = payloadOf(stream, "incident-changed");
		assertThat(announcement.propertyNames()).containsExactlyInAnyOrderElementsOf(CHANGED_FIELDS);
		assertThat(announcement.get("incidentId").longValue()).isEqualTo(CHANGE.incidentId());
		assertThat(announcement.get("occurredAt").stringValue()).isEqualTo(CHANGE.occurredAt().toString());
	}

	/** 화면이 여럿 열려 있으면 전부가 같은 변화를 받아야 한다. 하나만 받으면 나머지는 낡은 상태를 그린다. */
	@Test
	void announcesTheChangeToEveryOpenStream() throws Exception {
		MvcResult first = openStream();
		MvcResult second = openStream();

		this.broadcaster.broadcast(CHANGE);

		assertThat(eventNamesOf(first)).contains("incident-changed");
		assertThat(eventNamesOf(second)).contains("incident-changed");
	}

	/** 끝난 연결의 자리는 사라져야 한다. 남으면 화면을 열고 닫을 때마다 구독 목록이 자란다. */
	@Test
	void forgetsTheSubscriptionWhenTheStreamEnds() throws Exception {
		MvcResult stream = openStream();
		assertThat(this.broadcaster.subscriberCount()).isEqualTo(1);

		stream.getRequest().getAsyncContext().complete();

		assertThat(this.broadcaster.subscriberCount()).isZero();
	}

	private MvcResult openStream() throws Exception {
		MvcResult stream = this.mockMvc.perform(get("/api/incidents/stream")).andReturn();
		this.openedStreams.add(stream);
		return stream;
	}

	/**
	 * Stream은 Event 사이를 빈 줄로 나눈다. 지금까지 쓰인 만큼만 읽으므로 이 method를 부르는 시점까지
	 * 도착한 Event의 이름이 순서대로 나온다.
	 */
	private static List<String> eventNamesOf(MvcResult stream) throws Exception {
		return blocksOf(stream).stream().map((block) -> lineOf(block, "event:")).toList();
	}

	private JsonNode payloadOf(MvcResult stream, String eventName) throws Exception {
		String block = blocksOf(stream).stream()
			.filter((candidate) -> lineOf(candidate, "event:").equals(eventName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Event가 도착하지 않았다: name=" + eventName));
		return this.objectMapper.readTree(lineOf(block, "data:"));
	}

	private static List<String> blocksOf(MvcResult stream) throws Exception {
		return Arrays.stream(stream.getResponse().getContentAsString().split("\n\n"))
			.filter((block) -> !block.isBlank())
			.toList();
	}

	private static String lineOf(String block, String prefix) {
		return block.lines()
			.filter((line) -> line.startsWith(prefix))
			.map((line) -> line.substring(prefix.length()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("찾는 줄이 없다: prefix=" + prefix));
	}

}
