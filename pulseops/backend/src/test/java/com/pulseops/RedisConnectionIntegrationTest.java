package com.pulseops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * PulseOps가 실제 Redis 서버에 연결해 값을 읽고 쓸 수 있는지 검증한다.
 * <p>
 * Phase 3-1은 Metric을 만들지 않고 연결 기반만 준비하는 단계이므로, 검증 대상은 Spring Boot
 * Auto Configuration이 만든 연결 구성 그 자체이다. 직접 만든 Configuration이나 Template이
 * 없다는 것도 이 테스트가 함께 고정한다.
 * <p>
 * Kafka Listener는 시작하지 않는다. Redis 연결을 보는 테스트가 Broker 유무에 좌우되면 안 되기
 * 때문이며, 실제 Broker 연동은 Kafka Integration Test가 따로 담당한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class RedisConnectionIntegrationTest {

	/**
	 * Metric 집계는 Redis의 숫자 연산과 만료에 의존하므로 Redis 버전을 고정한다. Kafka Container를
	 * 고정한 것과 같은 이유이며, tag가 움직이면 재현되지 않는 테스트가 된다.
	 */
	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	/**
	 * 이 테스트 전용 Key 공간이다. Metric Key 형식을 흉내 내면 나중에 실제 Metric Key와 구분되지
	 * 않아, 남은 Key를 보고 어느 쪽이 만든 것인지 판단할 수 없게 된다.
	 */
	private static final String TEST_KEY_PREFIX = "pulseops:test:redis-bootstrap:";

	private static final Duration KEY_TTL = Duration.ofSeconds(60);

	@Autowired
	private StringRedisTemplate redisTemplate;

	/** 테스트마다 다른 Key를 써서 서로의 데이터를 건드리지 않는다. */
	private final String key = TEST_KEY_PREFIX + UUID.randomUUID();

	@AfterEach
	void removeTestKey() {
		this.redisTemplate.delete(this.key);
	}

	/**
	 * Redis Client 구현과 Template은 Auto Configuration이 준 것을 그대로 쓴다. 저장 값이 Key,
	 * 정수 counter, 정수 latency뿐이라 String 직렬화로 충분하고, Java 기본 직렬화를 쓰는
	 * {@code RedisTemplate<Object, Object>}는 숫자 증가 명령과도 맞지 않는다.
	 */
	@Test
	void usesLettuceBackedStringRedisTemplateFromAutoConfiguration() {
		assertThat(this.redisTemplate.getConnectionFactory()).isInstanceOf(LettuceConnectionFactory.class);
	}

	@Test
	void connectsToTheRedisContainer() {
		String pong = this.redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);

		assertThat(pong).isEqualTo("PONG");
	}

	@Test
	void writesAndReadsBackAValue() {
		this.redisTemplate.opsForValue().set(this.key, "redis-bootstrap", KEY_TTL);

		assertThat(this.redisTemplate.opsForValue().get(this.key)).isEqualTo("redis-bootstrap");
	}

	/**
	 * 만료를 기다려서 확인하지 않는다. 대기는 테스트를 느리고 불안정하게 만들 뿐이고, 확인하려는
	 * 것은 "TTL이 실제로 걸렸는가"이므로 Redis의 TTL 명령으로 남은 시간을 보면 충분하다.
	 */
	@Test
	void appliesTheRequestedTimeToLive() {
		this.redisTemplate.opsForValue().set(this.key, "redis-bootstrap", KEY_TTL);

		assertThat(this.redisTemplate.getExpire(this.key, TimeUnit.SECONDS)).isPositive()
			.isLessThanOrEqualTo(KEY_TTL.toSeconds());
	}

	/**
	 * Redis 전용 Testcontainers Module 없이 GenericContainer를 쓴다. Spring Boot는 Container
	 * Type이나 Image 이름으로 어떤 서비스인지 판단하는데, GenericContainer는 Type으로 구분되지
	 * 않고 Image 이름은 {@code @Bean}으로 선언한 Container에서는 읽히지 않는다. 그래서 어떤
	 * 서비스인지를 {@code name}으로 직접 알려 준다. 이 이름을 빼면 Redis로 인식되지 않아 연결
	 * 정보가 Context에 등록되지 않는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class RedisTestcontainersConfiguration {

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
		}

	}

}
