package com.pulseops.github;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Incident의 route template을 코드에서 찾을 검색어로 바꾼다.
 * <p>
 * endpoint는 실제 ID가 아니라 {@code /api/orders/{orderId}} 같은 template이다. 중괄호 안의 값은 요청마다
 * 다른 데이터이고 코드에 그런 형태로 적혀 있지도 않으므로, 그것을 검색어에 넣으면 아무것도 찾지 못하거나
 * 엉뚱한 파일이 걸린다. 실제 주문 번호를 검색하지 않는 것은 그 값이 개인의 요청을 가리키는 데이터이기
 * 때문이기도 하다.
 * <p>
 * 단계를 둘로 못 박는다. 정적 접두사로 먼저 찾고, 없으면 마지막 의미 있는 조각으로 한 번 더 찾는다.
 * 여기서 더 넓히면 검색은 언제나 무언가를 찾아내지만 그것이 관련 있다는 보장은 사라지고, GitHub의
 * 분당 호출 한도만 빠르게 소모한다. 두 번으로 못 찾는 endpoint는 못 찾는 것이 정답이다.
 */
public final class EndpointRoute {

	private static final String PATH_VARIABLE_OPEN = "{";

	private static final char SEGMENT_SEPARATOR = '/';

	private EndpointRoute() {
	}

	/**
	 * 검색어를 순서대로 만든다. 앞의 것이 더 구체적이므로 앞에서부터 시도한다.
	 * <p>
	 * 결과가 하나일 수 있다. 접두사가 조각 하나뿐이면 두 단계가 같은 문자열이 되고, 같은 검색을 두 번
	 * 보내는 것은 한도만 쓰고 결과는 같다.
	 */
	public static List<String> searchTerms(String endpoint) {
		String prefix = staticPrefix(endpoint);
		if (prefix.isEmpty()) {
			return List.of();
		}
		Set<String> terms = new LinkedHashSet<>();
		terms.add(prefix);
		String lastSegment = lastSegment(prefix);
		if (!lastSegment.isEmpty()) {
			terms.add(lastSegment);
		}
		return List.copyOf(terms);
	}

	/**
	 * 첫 path variable 앞까지가 코드에 그대로 적혀 있을 수 있는 부분이다. 그 뒤는 template 표기라
	 * 우리 표기와 코드의 표기가 같다는 보장이 없다.
	 */
	public static String staticPrefix(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return "";
		}
		String value = endpoint.strip();
		int variableAt = value.indexOf(PATH_VARIABLE_OPEN);
		if (variableAt >= 0) {
			value = value.substring(0, variableAt);
		}
		while (value.length() > 1 && value.charAt(value.length() - 1) == SEGMENT_SEPARATOR) {
			value = value.substring(0, value.length() - 1);
		}
		return value.equals("/") ? "" : value;
	}

	private static String lastSegment(String prefix) {
		int separatorAt = prefix.lastIndexOf(SEGMENT_SEPARATOR);
		return (separatorAt < 0) ? prefix : prefix.substring(separatorAt + 1);
	}

}
