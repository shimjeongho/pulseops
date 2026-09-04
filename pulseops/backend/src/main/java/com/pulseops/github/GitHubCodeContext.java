package com.pulseops.github;

import java.util.List;

/**
 * Incident 하나를 위해 GitHub에서 한 번에 모아 온 코드 맥락이다. 저장되기 전과 후에 같은 모양이며,
 * 외부 model에 보낼 때도 이 형태를 그대로 쓴다.
 * <p>
 * 이것은 "Incident 발생 당시의 repository"가 아니라 "그 시점을 기준으로 우리가 찾아낸 몇 개의 조각"이다.
 * 관련 file은 endpoint 문자열 검색으로 고르고 그 검색은 기본 branch만 본다. 그래서 그때는 있었지만
 * 지금은 지워졌거나 이름이 바뀐 file은 애초에 후보에도 오르지 못한다. 이 한계는 분석 결과의 limitations에
 * 그대로 남아야 하며, 이 record를 "snapshot"이라 부르되 완전한 복원으로 읽지 않는 이유이다.
 * <p>
 * 개수 상한을 이 record가 강제한다. 상한을 수집하는 쪽에만 두면 provider가 많이 돌려주거나 수집 순서가
 * 바뀌었을 때 그대로 통과해 DB와 prompt가 함께 커진다. 마지막으로 막는 자리는 값 자체여야 한다.
 * <p>
 * 비어 있을 수 있다. 관련 file을 찾지 못하는 것은 실패가 아니라 정상적인 검색 결과이며, 그 사실도
 * 기록으로 남을 값어치가 있다. 다만 근거가 하나도 없는 코드 기반 분석은 만들지 않는다.
 */
public record GitHubCodeContext(String repository, String ref, String sourceRoot, List<GitHubSourceFile> files,
		List<GitHubCommit> commits) {

	/**
	 * 담을 수 있는 최대 file 수이다.
	 * <p>
	 * 셋이면 대개 Controller와 그것이 부르는 자리까지 닿고, 그보다 늘리면 관련도가 낮은 file이 근거처럼
	 * 섞인다. GitHub 호출 수도 file 수에 비례해 늘어난다.
	 */
	public static final int MAX_FILES = 3;

	/** 담을 수 있는 최대 commit 수이다. 최근에 무엇이 바뀌었는지를 보는 목적이라 더 필요하지 않다. */
	public static final int MAX_COMMITS = 5;

	/**
	 * 모든 조각을 합쳐 담을 수 있는 최대 글자 수이다.
	 * <p>
	 * file마다의 상한만 두면 file 수를 곱한 만큼 입력이 커진다. 이 값은 그 곱을 막는다. 파일 셋이 모두
	 * 자기 상한에 가까우면 여기서 먼저 걸리며, 그때는 순위가 낮은 파일이 빠진다. 저장 열의 크기와
	 * prompt 크기를 예측 가능하게 만드는 것은 파일별 상한이 아니라 이 값이다.
	 */
	public static final int MAX_TOTAL_SNIPPET_LENGTH = 10_000;

	public GitHubCodeContext {
		repository = requirePresent(repository, "repository");
		ref = requirePresent(ref, "ref");
		sourceRoot = requirePresent(sourceRoot, "sourceRoot");
		files = requireBoundedFiles(files);
		commits = requireBoundedCommits(commits);
	}

	/** 관련 source를 하나도 찾지 못했다는 뜻이다. 실패가 아니라 정상적인 검색 결과이다. */
	public boolean hasNoSource() {
		return this.files.isEmpty();
	}

	private static List<GitHubSourceFile> requireBoundedFiles(List<GitHubSourceFile> files) {
		if (files == null) {
			throw new IllegalArgumentException("files가 없다");
		}
		if (files.size() > MAX_FILES) {
			throw new IllegalArgumentException("files가 너무 많다: size=" + files.size() + " max=" + MAX_FILES);
		}
		int total = files.stream().mapToInt((file) -> file.snippet().length()).sum();
		if (total > MAX_TOTAL_SNIPPET_LENGTH) {
			throw new IllegalArgumentException(
					"source 조각의 합이 상한을 넘었다: length=" + total + " max=" + MAX_TOTAL_SNIPPET_LENGTH);
		}
		return List.copyOf(files);
	}

	private static List<GitHubCommit> requireBoundedCommits(List<GitHubCommit> commits) {
		if (commits == null) {
			throw new IllegalArgumentException("commits가 없다");
		}
		if (commits.size() > MAX_COMMITS) {
			throw new IllegalArgumentException("commits가 너무 많다: size=" + commits.size() + " max=" + MAX_COMMITS);
		}
		return List.copyOf(commits);
	}

	private static String requirePresent(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "가 비어 있다");
		}
		return value;
	}

}
