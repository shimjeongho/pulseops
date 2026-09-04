package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * commit 하나에 무엇이 담기고 무엇이 담기지 않는지를 고정한다.
 * <p>
 * 담기지 않는 것이 더 중요하다. 작성자를 담지 않는 것은 자리를 아껴서가 아니라 이 기능이 누가 잘못했는지를
 * 가리는 것이 아니기 때문이며, 그 규칙이 코드에서만 지켜지고 확인되지 않으면 언젠가 편의를 이유로 추가된다.
 */
class GitHubCommitTest {

	private static final String SHA = "0f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6";

	private static final Instant COMMITTED_AT = Instant.parse("2026-09-01T05:00:00Z");

	private static final String PATH = "demo-shop/backend/src/main/java/OrderController.java";

	/** 이 record가 담을 수 있는 값이 넷뿐이라는 것이 곧 사람 정보가 나갈 수 없다는 뜻이다. */
	@Test
	void carriesNothingThatIdentifiesAPerson() {
		assertThat(GitHubCommit.class.getRecordComponents()).extracting("name")
			.containsExactly("sha", "message", "committedAt", "paths");
	}

	/** 긴 message는 자른다. 길이 때문에 commit을 통째로 버리면 무엇이 바뀌었는지가 사라진다. */
	@Test
	void shortensALongMessageInsteadOfDroppingTheCommit() {
		String longMessage = "제목\n\n" + "본문 ".repeat(1000);

		String bounded = GitHubCommit.boundedMessage(longMessage);

		assertThat(bounded.length()).isEqualTo(GitHubCommit.MESSAGE_MAX_LENGTH);
		assertThat(bounded).startsWith("제목");
	}

	@Test
	void keepsAShortMessageAsItIs() {
		assertThat(GitHubCommit.boundedMessage("  주문 검증을 고쳤다  ")).isEqualTo("주문 검증을 고쳤다");
	}

	/**
	 * 같은 commit이 여러 파일의 이력에서 나오므로 경로를 합친다. 합치지 않으면 같은 sha가 목록에 두 번
	 * 올라가 최근 변경이 실제보다 많아 보인다.
	 */
	@Test
	void mergesThePathsOfTheSameCommitSeenThroughDifferentFiles() {
		GitHubCommit fromController = new GitHubCommit(SHA, "고쳤다", COMMITTED_AT, List.of(PATH));

		GitHubCommit merged = fromController.mergePaths(List.of("demo-shop/OrderService.java", PATH));

		assertThat(merged.paths()).containsExactly(PATH, "demo-shop/OrderService.java");
		assertThat(merged.sha()).isEqualTo(SHA);
	}

	/** 어느 파일 때문에 이 commit을 봤는지 모르면 그 commit이 왜 근거인지도 설명할 수 없다. */
	@Test
	void rejectsACommitThatDoesNotSayWhichFileItWasFoundThrough() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCommit(SHA, "고쳤다", COMMITTED_AT, List.of()));
	}

	@Test
	void rejectsACommitWithoutATime() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCommit(SHA, "고쳤다", null, List.of(PATH)));
	}

	@Test
	void rejectsAMessageOverTheLimit() {
		String tooLong = "x".repeat(GitHubCommit.MESSAGE_MAX_LENGTH + 1);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubCommit(SHA, tooLong, COMMITTED_AT, List.of(PATH)));
	}

}
