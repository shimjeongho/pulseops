package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 파일에서 무엇을 잘라 내고 무엇을 잘라 내지 않는지를 고정한다.
 * <p>
 * 여기서 만들어진 문자열이 그대로 외부 model로 나가고 그대로 DB에 남는다. 상한이 사라지거나 잘못된
 * 자리를 잘라 내는 것은 파일이 커지기 전까지 아무 데도 드러나지 않으므로, 값으로 확인해 둔다.
 */
class GitHubSourceFileTest {

	private static final String PATH = "demo-shop/backend/src/main/java/com/pulseops/demoshop/order/OrderController.java";

	private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

	private static final String TERM = "/api/orders";

	/** 일치한 줄만이 아니라 그 아래 본문까지 담는다. annotation 한 줄만 보내면 경로의 존재만 전달된다. */
	@Test
	void keepsTheMatchedLineTogetherWithTheCodeAroundIt() {
		String content = """
				package com.pulseops.demoshop.order;

				@RestController
				@RequestMapping("/api/orders")
				public class OrderController {

					public OrderResponse create(OrderRequest request) {
						return this.orderService.create(request);
					}

				}
				""";

		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, content, TERM).orElseThrow();

		assertThat(file.snippet()).contains("@RequestMapping(\"/api/orders\")");
		assertThat(file.snippet()).contains("public OrderResponse create(OrderRequest request)");
		assertThat(file.path()).isEqualTo(PATH);
		assertThat(file.revisionSha()).isEqualTo(SHA);
	}

	/** 파일 전체가 조각 안에 들어갔으면 잘리지 않은 것이다. 잘렸다고 표시하면 없는 한계를 알린다. */
	@Test
	void reportsThatNothingWasCutWhenTheWholeFileFits() {
		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, "class A { /* /api/orders */ }\n", TERM).orElseThrow();

		assertThat(file.truncated()).isFalse();
	}

	/**
	 * 아주 큰 파일이라도 조각은 상한 안에 있다. 파일 전체가 실려 나가면 저장 열과 prompt가 함께 커지고,
	 * 그 사실이 드러나는 시점은 대개 저장에 실패하는 순간이다.
	 */
	@Test
	void neverSendsAWholeLargeFile() {
		String huge = IntStream.range(0, 5000)
			.mapToObj((line) -> (line == 4000) ? "  // %s handler".formatted(TERM) : "  int value%d = %d;".formatted(line, line))
			.collect(Collectors.joining("\n"));

		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, huge, TERM).orElseThrow();

		assertThat(file.snippet().length()).isLessThanOrEqualTo(GitHubSourceFile.MAX_SNIPPET_LENGTH);
		assertThat(file.snippet().lines().count()).isLessThanOrEqualTo(GitHubSourceFile.MAX_SNIPPET_LINES);
		assertThat(file.snippet()).contains("/api/orders handler");
		assertThat(file.snippet()).contains("value3999");
		assertThat(file.snippet()).doesNotContain("value0 = 0;");
		assertThat(file.truncated()).isTrue();
	}

	/** 줄 하나가 아주 길어도 글자 수 상한이 먼저 막는다. 줄 수만 세면 상한이 없는 것과 같다. */
	@Test
	void boundsBySizeEvenWhenThereAreFewLines() {
		String oneHugeLine = "// %s %s".formatted(TERM, "x".repeat(20_000));

		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, oneHugeLine + "\n", TERM).orElse(null);

		assertThat(file).isNull();
	}

	/**
	 * 검색어가 없는 파일에서는 조각을 만들지 않는다. 앞부분을 대신 보내면 관련 없는 코드가 관련 있는
	 * 근거로 저장되고, 상한만 채우면서 판단은 흐려진다.
	 */
	@Test
	void refusesToFallBackToTheStartOfAnUnrelatedFile() {
		String unrelated = "package com.pulseops.demoshop.payment;\n\nclass PaymentGateway {\n}\n";

		assertThat(GitHubSourceFile.of(PATH, SHA, unrelated, TERM)).isEqualTo(Optional.empty());
	}

	/**
	 * credential처럼 보이는 줄은 조각에서 지운다.
	 * <p>
	 * 이것은 secret 탐지기가 아니라 마지막 그물이다. 실제 방어는 source root 제한과 확장자 allowlist이며,
	 * 그 둘을 통과한 application source에 key가 남아 있는 경우는 사고에 가깝다. 그래도 그 한 줄이 외부
	 * model과 DB로 나가는 것을 값싸게 막을 수 있어 여기서 한 번 더 거른다.
	 */
	@Test
	void removesLinesThatLookLikeCredentials() {
		String content = """
				class OrderController {
					// /api/orders
					private static final String token = "ghp_notarealtokenvalue";
				}
				""";

		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, content, TERM).orElseThrow();

		assertThat(file.snippet()).doesNotContain("ghp_notarealtokenvalue");
		assertThat(file.snippet()).contains("credential로 보이는 줄을 제거했다");
	}

	@Test
	void removesAPrivateKeyBlockHeader() {
		String content = "// /api/orders\n-----BEGIN RSA PRIVATE KEY-----\n";

		GitHubSourceFile file = GitHubSourceFile.of(PATH, SHA, content, TERM).orElseThrow();

		assertThat(file.snippet()).doesNotContain("BEGIN RSA PRIVATE KEY");
	}

	/** 상한을 넘긴 값을 잘라 담지 않고 거절한다. 잘라 담으면 계약을 어긴 값이 정상 기록으로 남는다. */
	@Test
	void rejectsASnippetThatIsBuiltOverTheLimit() {
		String tooLong = "x".repeat(GitHubSourceFile.MAX_SNIPPET_LENGTH + 1);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubSourceFile(PATH, SHA, tooLong, false));
	}

	@Test
	void rejectsAFileWithoutTheRevisionItWasReadFrom() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new GitHubSourceFile(PATH, "  ", "code", false));
	}

}
