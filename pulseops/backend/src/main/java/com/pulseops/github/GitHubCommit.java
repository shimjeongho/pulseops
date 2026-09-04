package com.pulseops.github;

import java.time.Instant;
import java.util.List;

/**
 * Incident가 열리기 전에 관련 file을 건드린 commit 하나이다.
 * <p>
 * 작성자를 담지 않는다. 이름도 email도 담지 않는 것은 자리를 아끼기 위해서가 아니라 이 기능이 누가
 * 잘못했는지를 가리는 것이 아니기 때문이다. 사람 이름이 근거 목록에 올라가는 순간 분석은 원인을 찾는
 * 일에서 책임을 묻는 일로 성격이 바뀌고, 그 이름은 원인 추정에 아무 정보도 더하지 않는다. email은
 * 개인정보이기도 해서 외부 model에 보낼 이유가 더더욱 없다.
 * <p>
 * {@code paths}는 이 commit을 찾을 때 기준이 된 관련 file 경로이다. commit이 실제로 건드린 파일 전체가
 * 아니다. 우리가 아는 것은 "이 경로의 이력을 물었더니 이 commit이 나왔다"까지이며, 그 이상을 적으면
 * 조회하지 않은 사실을 기록하는 것이 된다.
 * <p>
 * {@code message}는 사람이 자유롭게 쓴 문장이라 신뢰할 수 없는 데이터이다. 지시문처럼 보이는 문장이
 * 들어 있을 수 있으며, 그것을 지시로 읽지 않게 하는 일은 prompt의 구조가 맡는다.
 * <p>
 * 이 commit이 Incident의 원인이라는 뜻이 아니다. 여기 있는 것은 시간 순서뿐이며, 먼저 일어난 일이
 * 원인이라는 보장은 없다. 그 구분을 흐리지 않는 것은 이 record가 아니라 prompt와 화면이 맡는다.
 */
public record GitHubCommit(String sha, String message, Instant committedAt, List<String> paths) {

	/**
	 * commit message에서 담는 최대 길이이다. 제목과 첫 문단이면 무엇을 바꾸려 했는지는 대체로 드러나고,
	 * 그 아래로는 template과 서명이 이어져 근거는 늘지 않으면서 입력만 커진다.
	 */
	public static final int MESSAGE_MAX_LENGTH = 500;

	private static final int SHA_MAX_LENGTH = 40;

	private static final int PATH_MAX_LENGTH = 400;

	public GitHubCommit {
		sha = requireBounded(sha, "sha", SHA_MAX_LENGTH);
		message = requireBounded(message, "message", MESSAGE_MAX_LENGTH);
		if (committedAt == null) {
			throw new IllegalArgumentException("committedAt이 없다");
		}
		if (paths == null || paths.isEmpty()) {
			throw new IllegalArgumentException("paths가 비어 있다");
		}
		paths.forEach((path) -> requireBounded(path, "paths 항목", PATH_MAX_LENGTH));
		paths = List.copyOf(paths);
	}

	/** 길면 자른다. 길이 때문에 commit 하나를 통째로 버리면 최근에 무엇이 바뀌었는지가 사라진다. */
	public static String boundedMessage(String message) {
		String firstLines = (message != null) ? message.strip() : "";
		return (firstLines.length() > MESSAGE_MAX_LENGTH) ? firstLines.substring(0, MESSAGE_MAX_LENGTH) : firstLines;
	}

	/** 같은 commit이 여러 file의 이력에서 나오므로, 경로를 합쳐 하나로 만든다. */
	public GitHubCommit mergePaths(List<String> otherPaths) {
		List<String> merged = java.util.stream.Stream.concat(this.paths.stream(), otherPaths.stream())
			.distinct()
			.toList();
		return new GitHubCommit(this.sha, this.message, this.committedAt, merged);
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
