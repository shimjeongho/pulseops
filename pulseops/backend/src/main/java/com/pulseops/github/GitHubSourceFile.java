package com.pulseops.github;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Incident와 관련 있어 보이는 source file 하나에서 잘라 낸 조각이다.
 * <p>
 * 파일 전체를 담지 않는다. 이 조각이 그대로 외부 model에 실려 나가고 그대로 DB에 남으므로, 담기는 양은
 * 파일 크기가 아니라 우리가 정한 상한이 결정해야 한다. 관련 있는 곳은 route가 적힌 자리 근처이고,
 * 그 바깥은 근거가 되지 않으면서 입력과 저장만 키운다.
 * <p>
 * {@code revisionSha}는 이 조각을 실제로 읽어 온 blob의 SHA이다. "main을 봤다"만 남기면 나중에 같은
 * 경로를 다시 읽었을 때 그때와 같은 내용을 보고 있는지 말할 수 없다. 어느 판을 봤는지는 이 값만 안다.
 * <p>
 * {@code truncated}는 상한 때문에 잘렸다는 표시이다. 이 값을 함께 보내지 않으면 읽는 쪽도 model도
 * 파일 전체를 본 것으로 오해하고, 조각 밖에 있는 코드를 두고 "없다"고 단정하게 된다.
 * <p>
 * 이 안의 문자열은 전부 신뢰할 수 없는 데이터이다. 주석이나 문자열 literal에 지시문처럼 보이는 문장이
 * 들어 있을 수 있으며, 그것을 지시로 읽지 않게 하는 일은 prompt의 구조가 맡는다.
 */
public record GitHubSourceFile(String path, String revisionSha, String snippet, boolean truncated) {

	/** 조각 하나가 담을 수 있는 최대 줄 수이다. 사람이 한 화면에서 훑을 수 있는 범위를 넘지 않는다. */
	public static final int MAX_SNIPPET_LINES = 200;

	/** 줄이 아주 길 수도 있으므로 글자 수로도 막는다. 두 상한 중 먼저 걸리는 쪽이 조각을 끝낸다. */
	public static final int MAX_SNIPPET_LENGTH = 4000;

	/** 일치한 줄 위로 남기는 줄 수이다. route가 적힌 annotation 위의 선언부를 담는 정도이다. */
	private static final int CONTEXT_LINES_BEFORE = 20;

	/** 일치한 줄 아래로 남기는 줄 수이다. route 아래의 method 본문이 여기에 담긴다. */
	private static final int CONTEXT_LINES_AFTER = 100;

	private static final int PATH_MAX_LENGTH = 400;

	private static final int REVISION_SHA_MAX_LENGTH = 40;

	/**
	 * 조각에서 지워 버릴 줄을 알아보는 최소한의 표시이다.
	 * <p>
	 * 이것은 secret 탐지기가 아니다. 실제 방어는 source root 제한과 확장자 allowlist이며, 그 두 경계를
	 * 통과한 application source에 credential이 남아 있는 경우는 사고에 가깝다. 그래도 그 한 줄이 외부
	 * model과 DB로 나가는 것을 막을 값싼 방법이 있어 여기서 한 번 더 거른다. 넓게 잡지 않는 이유는,
	 * 패턴이 늘어나면 정상 코드가 지워져 분석이 보지 못한 것을 보지 못한 줄도 모르게 되기 때문이다.
	 */
	private static final Pattern SECRET_LINE = Pattern
		.compile("(?i)-----BEGIN[^-]*PRIVATE KEY-----|(password|secret|token|api[-_]?key)\s*[=:]\s*[\"'][^\"']+[\"']");

	private static final String MASKED_LINE = "// [PulseOps] credential로 보이는 줄을 제거했다";

	public GitHubSourceFile {
		path = requireBounded(path, "path", PATH_MAX_LENGTH);
		revisionSha = requireBounded(revisionSha, "revisionSha", REVISION_SHA_MAX_LENGTH);
		snippet = requireBounded(snippet, "snippet", MAX_SNIPPET_LENGTH);
		if (snippet.lines().count() > MAX_SNIPPET_LINES) {
			throw new IllegalArgumentException("snippet의 줄 수가 상한을 넘었다: max=" + MAX_SNIPPET_LINES);
		}
	}

	/**
	 * 검색어가 나타난 자리를 중심으로 앞뒤를 함께 잘라 낸다.
	 * <p>
	 * 앞뒤를 함께 남기는 이유는 Java에서 route가 적힌 줄이 대개 annotation 한 줄이고, 실제로 무엇을
	 * 하는지는 그 아래 method 본문에 있기 때문이다. 그 한 줄만 보내면 경로가 존재한다는 사실만 전달된다.
	 * <p>
	 * 검색어가 파일 어디에도 없으면 조각을 만들지 않고 비어 있는 결과를 준다. 그때 파일 앞부분을 대신
	 * 보내는 것은 관련 없는 코드를 근거처럼 보내는 일이고, 상한만 채우면서 판단은 흐린다.
	 */
	public static Optional<GitHubSourceFile> of(String path, String revisionSha, String content, String searchTerm) {
		String[] lines = content.split("\n", -1);
		int matchedLine = indexOfMatch(lines, searchTerm);
		if (matchedLine < 0) {
			return Optional.empty();
		}

		int from = Math.max(0, matchedLine - CONTEXT_LINES_BEFORE);
		int end = Math.min(lines.length, matchedLine + CONTEXT_LINES_AFTER + 1);
		StringBuilder snippet = new StringBuilder();
		int taken = 0;
		for (int index = from; index < end && taken < MAX_SNIPPET_LINES; index++) {
			String line = mask(lines[index]);
			if (snippet.length() + line.length() + 1 > MAX_SNIPPET_LENGTH) {
				break;
			}
			snippet.append(line).append('\n');
			taken++;
		}

		String text = snippet.toString().stripTrailing();
		if (text.isBlank()) {
			return Optional.empty();
		}
		boolean truncated = (from > 0) || (from + taken < lines.length);
		return Optional.of(new GitHubSourceFile(path, revisionSha, text, truncated));
	}

	private static String mask(String line) {
		return SECRET_LINE.matcher(line).find() ? MASKED_LINE : line;
	}

	private static int indexOfMatch(String[] lines, String searchTerm) {
		for (int index = 0; index < lines.length; index++) {
			if (lines[index].contains(searchTerm)) {
				return index;
			}
		}
		return -1;
	}

	private static String requireBounded(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "가 비어 있다");
		}
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(field + "가 상한을 넘었다: length=" + value.length() + " max=" + maxLength);
		}
		return value;
	}

}
