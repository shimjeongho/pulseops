package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * route template이 어떤 검색어가 되는지를 고정한다.
 * <p>
 * 이 변환이 곧 "어느 코드를 찾을 것인가"이다. 여기가 흔들리면 관련 없는 파일이 Incident의 근거로
 * 저장되고, 그 뒤의 어떤 상한도 그 잘못을 되돌리지 못한다.
 */
class EndpointRouteTest {

	/** path variable 자리는 요청마다 다른 데이터라 코드 어디에도 그 형태로 적혀 있지 않다. */
	@Test
	void dropsEverythingFromTheFirstPathVariable() {
		assertThat(EndpointRoute.staticPrefix("/api/orders/{orderId}")).isEqualTo("/api/orders");
		assertThat(EndpointRoute.staticPrefix("/api/orders/{orderId}/items/{itemId}")).isEqualTo("/api/orders");
	}

	/** 변수가 없으면 경로 전체가 그대로 쓸 수 있는 검색어이다. */
	@Test
	void keepsARouteThatHasNoPathVariable() {
		assertThat(EndpointRoute.staticPrefix("/api/products")).isEqualTo("/api/products");
	}

	/**
	 * 검색을 두 단계로 못 박는다. 더 구체적인 접두사를 먼저 쓰고, 그것으로 찾지 못했을 때만 마지막
	 * 조각으로 한 번 더 시도한다. 셋 이상으로 넓히면 검색은 언제나 무언가를 찾아내지만 그것이 관련
	 * 있다는 보장은 사라진다.
	 */
	@Test
	void triesTheStaticPrefixFirstAndThenTheLastSegment() {
		assertThat(EndpointRoute.searchTerms("/api/orders/{orderId}")).containsExactly("/api/orders", "orders");
	}

	/**
	 * 두 단계가 같은 문자열이 되면 한 번만 시도한다. 같은 검색을 두 번 보내는 것은 결과는 같으면서
	 * GitHub의 분당 한도만 한 번 더 쓴다.
	 */
	@Test
	void doesNotRepeatTheSameSearchTwice() {
		assertThat(EndpointRoute.searchTerms("/orders")).containsExactly("/orders", "orders");
		assertThat(EndpointRoute.searchTerms("orders")).containsExactly("orders");
	}

	/** 실제 ID를 검색어로 쓰지 않는다. 그 값은 개인의 요청 하나를 가리키는 데이터이다. */
	@Test
	void neverSearchesForAConcreteIdentifier() {
		assertThat(EndpointRoute.searchTerms("/api/orders/{orderId}")).noneMatch((term) -> term.contains("{"));
	}

	/** 찾을 것이 없는 경로에서는 검색을 시작하지도 않는다. */
	@ParameterizedTest
	@ValueSource(strings = { "/", "/{orderId}", "   " })
	void refusesToSearchWhenNothingStaticRemains(String endpoint) {
		assertThat(EndpointRoute.searchTerms(endpoint)).isEqualTo(List.of());
	}

	@Test
	void treatsAMissingEndpointAsNothingToSearch() {
		assertThat(EndpointRoute.searchTerms(null)).isEqualTo(List.of());
	}

	/** 끝의 구분자는 같은 경로의 다른 표기일 뿐이라 검색어가 갈라지면 안 된다. */
	@Test
	void ignoresATrailingSeparator() {
		assertThat(EndpointRoute.staticPrefix("/api/orders/")).isEqualTo("/api/orders");
		assertThat(EndpointRoute.staticPrefix("/api/orders/{orderId}")).isEqualTo(EndpointRoute.staticPrefix("/api/orders/"));
	}

}
