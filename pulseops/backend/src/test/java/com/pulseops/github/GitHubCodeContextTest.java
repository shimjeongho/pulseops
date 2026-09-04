package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 맥락 하나가 담을 수 있는 양의 상한을 고정한다.
 * <p>
 * 이 상한이 마지막 그물이다. 수집하는 쪽에만 상한을 두면 provider가 많이 돌려주거나 수집 순서가 바뀌었을
 * 때 그대로 통과해 DB와 prompt가 함께 커지고, 그것을 알아채는 시점은 저장에 실패하는 순간이다.
 */
class GitHubCodeContextTest {

	private static final String REPOSITORY = "pulseops-test/pulseops";

	private static final String REF = "main";

	private static final String SOURCE_ROOT = "demo-shop/backend/src/main/java";

	@Test
	void rejectsMoreFilesThanTheLimit() {
		List<GitHubSourceFile> tooMany = IntStream.rangeClosed(0, GitHubCodeContext.MAX_FILES)
			.mapToObj((index) -> file("A%d.java".formatted(index), "code"))
			.toList();

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, tooMany, List.of()));
	}

	@Test
	void rejectsMoreCommitsThanTheLimit() {
		List<GitHubCommit> tooMany = IntStream.rangeClosed(0, GitHubCodeContext.MAX_COMMITS)
			.mapToObj((index) -> new GitHubCommit("sha%d".formatted(index), "변경했다", Instant.EPOCH, List.of("A.java")))
			.toList();

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, List.of(), tooMany));
	}

	/**
	 * 파일마다의 상한만으로는 부족하다. 상한에 가까운 조각 셋이면 합계는 세 배가 되고, prompt 크기와
	 * 저장 열의 크기는 그 합계가 정한다.
	 */
	@Test
	void rejectsSnippetsThatAreBoundedOneByOneButTooLargeTogether() {
		String nearLimit = "x".repeat(GitHubSourceFile.MAX_SNIPPET_LENGTH);
		List<GitHubSourceFile> files = List.of(file("A.java", nearLimit), file("B.java", nearLimit),
				file("C.java", nearLimit));

		assertThat(files).hasSize(GitHubCodeContext.MAX_FILES);
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, files, List.of()));
	}

	/**
	 * 관련 코드를 찾지 못한 맥락도 정상적인 맥락이다. 그것을 실패로 다루면 같은 검색을 누를 때마다
	 * 반복하게 되고, "찾아봤지만 없었다"는 사실이 아무 데도 남지 않는다.
	 */
	@Test
	void acceptsAContextThatFoundNothing() {
		GitHubCodeContext context = new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, List.of(), List.of());

		assertThat(context.hasNoSource()).isTrue();
	}

	@Test
	void reportsThatItHasSourceWhenAFileWasFound() {
		GitHubCodeContext context = new GitHubCodeContext(REPOSITORY, REF, SOURCE_ROOT, List.of(file("A.java", "code")),
				List.of());

		assertThat(context.hasNoSource()).isFalse();
	}

	/** 어느 repository와 어느 branch를 봤는지 없이 저장되면 나중에 무엇도 되짚을 수 없다. */
	@Test
	void rejectsAContextThatDoesNotSayWhereItCameFrom() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCodeContext(" ", REF, SOURCE_ROOT, List.of(), List.of()));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCodeContext(REPOSITORY, " ", SOURCE_ROOT, List.of(), List.of()));
	}

	private static GitHubSourceFile file(String name, String snippet) {
		return new GitHubSourceFile(SOURCE_ROOT + "/" + name, "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", snippet,
				false);
	}

}
