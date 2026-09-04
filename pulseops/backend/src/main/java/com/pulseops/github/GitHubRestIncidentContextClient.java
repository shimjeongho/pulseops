package com.pulseops.github;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * GitHub REST API를 직접 불러 Incident 관련 코드 맥락을 모은다.
 * <p>
 * 비공식 Java client를 쓰지 않고 이미 있는 {@link RestClient}를 쓴다. 이 기능이 부르는 것은 공식
 * endpoint 셋뿐이고, 그것을 위해 dependency를 더하면 그쪽이 끌고 오는 HTTP stack과 JSON 판이 이
 * Application의 것과 같은 classpath에 놓인다. Phase 9의 Claude 호출이 이미 같은 판단으로 RestClient를
 * 쓰고 있어 외부 API를 부르는 방식도 하나로 유지된다.
 * <p>
 * 자동 재시도를 하지 않는다. 실패하면 그대로 실패로 알리고 사람이 다시 누르게 한다. GitHub의 한도는
 * 분 단위이고 특히 code search는 분당 열 번뿐이라, 실패한 자리에서 곧바로 다시 부르는 것은 이미 넘긴
 * 한도에 요청을 더 보내는 일이 된다. Kafka 소비의 재시도와도 아무 관계가 없다. 그쪽은 사람이 없는
 * 자리에서 도는 흐름이라 스스로 다시 시도해야 하지만, 여기는 사람이 화면 앞에 있다.
 * <p>
 * 호출 수를 미리 못 박는다. 검색은 최대 두 번이고, 고른 file 하나마다 이력 조회 한 번과 내용 조회 한
 * 번이다. file은 최대 셋이므로 맥락 하나를 만드는 데 드는 호출은 최대 여덟 번이다. 이 수가 코드에서
 * 바로 세어지지 않으면 한도는 언젠가 조용히 넘긴다.
 * <p>
 * 응답 본문을 log에 남기지 않는다. source 내용과 commit message는 그 자체가 신뢰할 수 없는 외부
 * 데이터이고 양도 크다. 인증 header는 어떤 형태로도 남기지 않는다.
 */
public class GitHubRestIncidentContextClient implements IncidentGitHubContextClient {

	/** 검색이 한 번에 받아 오는 결과 수이다. 이 중에서 거르고 순위를 매겨 소수만 고른다. */
	static final int SEARCH_PAGE_SIZE = 20;

	/** file 하나의 이력에서 받아 오는 commit 수이다. 첫 번째가 그 시점의 판이고 나머지가 최근 변경이다. */
	static final int COMMIT_PAGE_SIZE = GitHubCodeContext.MAX_COMMITS;

	/**
	 * 최근 변경으로 볼 구간이다.
	 * <p>
	 * 이보다 오래된 commit은 맥락에 담지 않는다. Incident 직전에 무엇이 바뀌었는지를 보려는 것인데
	 * 구간을 넓히면 한참 전의 정상적인 변경까지 "최근 변경"이라는 이름으로 근거 목록에 올라간다.
	 * 파일 내용을 어느 판에서 읽을지는 이 구간과 무관하게 그 시점의 마지막 commit이 정한다.
	 */
	static final Duration RECENT_COMMIT_WINDOW = Duration.ofDays(7);

	private static final String SOURCE_FILE_EXTENSION = ".java";

	/** Code Search가 이해하는 확장자 한정자이다. 우리가 다시 거르지만 검색 단계에서 먼저 좁힌다. */
	private static final String SEARCH_EXTENSION = "java";

	private static final String PARENT_SEGMENT = "..";

	private static final String CONTROLLER_NAME = "Controller";

	private static final String SERVICE_NAME = "Service";

	private static final String RATE_LIMIT_REMAINING_HEADER = "x-ratelimit-remaining";

	private static final String RATE_LIMIT_EXHAUSTED = "0";

	private static final Logger logger = LoggerFactory.getLogger(GitHubRestIncidentContextClient.class);

	private final RestClient restClient;

	private final GitHubProperties properties;

	public GitHubRestIncidentContextClient(RestClient restClient, GitHubProperties properties) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public GitHubCodeContext collect(IncidentGitHubContextQuery query) {
		Optional<SourceMatch> match = findSource(query.endpoint());
		if (match.isEmpty()) {
			logger.info("Incident endpoint와 관련된 source를 찾지 못했다. repository={} matchedFiles=0",
					this.properties.repositoryFullName());
			return context(List.of(), List.of());
		}
		return build(match.get(), query.openedAt());
	}

	/**
	 * 정적 접두사로 먼저 찾고, 못 찾으면 마지막 조각으로 한 번 더 찾는다.
	 * <p>
	 * 결과를 낸 검색어를 그대로 들고 나간다. 뒤에서 파일 안의 어느 줄이 관련 있는지 가릴 때 같은
	 * 문자열을 써야 하기 때문이다. 검색을 통과시킨 근거와 조각을 잘라 낸 근거가 다르면, 파일은 맞게
	 * 골랐는데 엉뚱한 곳을 잘라 보내게 된다.
	 */
	private Optional<SourceMatch> findSource(String endpoint) {
		for (String term : EndpointRoute.searchTerms(endpoint)) {
			List<String> paths = searchPaths(term);
			if (!paths.isEmpty()) {
				return Optional.of(new SourceMatch(term, paths));
			}
		}
		return Optional.empty();
	}

	private List<String> searchPaths(String term) {
		GitHubCodeSearchResponse response = get((uriBuilder) -> uriBuilder.path("/search/code")
			.queryParam("q", searchQuery(term))
			.queryParam("per_page", SEARCH_PAGE_SIZE)
			.build(), GitHubCodeSearchResponse.class).orElse(null);
		List<GitHubCodeSearchResponse.Item> items = (response != null && response.items() != null) ? response.items()
				: List.of();
		return items.stream()
			.map(GitHubCodeSearchResponse.Item::path)
			.filter(this::acceptable)
			.distinct()
			.sorted(Comparator.comparingInt(GitHubRestIncidentContextClient::rank)
				.thenComparing(Comparator.naturalOrder()))
			.limit(GitHubCodeContext.MAX_FILES)
			.toList();
	}

	/**
	 * 검색 범위를 설정된 repository와 source root 안으로 못 박는다.
	 * <p>
	 * 이 한정자들이 없으면 같은 검색어가 GitHub 전체를 훑고, 관계없는 남의 코드가 우리 Incident의
	 * 근거로 저장된다. 확장자까지 좁히는 것은 설정 파일과 script를 애초에 후보에서 빼기 위해서이다.
	 */
	private String searchQuery(String term) {
		return "%s repo:%s path:%s extension:%s in:file".formatted(term, this.properties.repositoryFullName(),
				this.properties.sourceRoot(), SEARCH_EXTENSION);
	}

	/**
	 * source root 밖이거나 application source가 아닌 경로를 버린다.
	 * <p>
	 * 확장자 allowlist가 secret을 막는 1차 경계이다. {@code .env}와 pem, 운영 설정 yml은 이 조건 하나로
	 * 전부 빠지므로, 무엇이 위험한 파일인지 이름으로 추측하는 검사기를 따로 두지 않는다.
	 * <p>
	 * {@code ..}가 든 경로도 버린다. GitHub이 그런 경로를 돌려줄 이유는 없지만, 우리가 그 값을 그대로
	 * 이어 붙여 다음 요청의 경로로 쓰기 때문에 확인하는 자리가 한 번은 있어야 한다.
	 */
	private boolean acceptable(String path) {
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(PARENT_SEGMENT)) {
			return false;
		}
		return path.startsWith(this.properties.sourceRoot() + "/") && path.endsWith(SOURCE_FILE_EXTENSION);
	}

	/**
	 * 파일을 고르는 순서를 코드에 적어 둔다.
	 * <p>
	 * 검색 점수를 쓰지 않는다. 그 값은 GitHub이 정하고 같은 질의라도 시점에 따라 달라져, 같은 Incident를
	 * 두 번 수집하면 다른 파일이 저장될 수 있다. route를 다루는 곳은 Controller이고 그 다음이 그것이
	 * 부르는 Service이므로, 그 두 이름을 순서로 삼으면 결과가 실행마다 같아진다.
	 */
	private static int rank(String path) {
		String fileName = path.substring(path.lastIndexOf('/') + 1);
		if (fileName.contains(CONTROLLER_NAME)) {
			return 0;
		}
		return fileName.contains(SERVICE_NAME) ? 1 : 2;
	}

	/**
	 * 고른 파일마다 그 시점의 판을 읽고 최근 변경을 모은다.
	 * <p>
	 * 조각의 합이 상한에 닿으면 그 파일을 담지 않는다. 억지로 담으면 저장 열과 prompt가 함께 커지고,
	 * 담기지 못한 파일은 이미 담긴 것보다 순위가 낮았던 파일이다.
	 */
	private GitHubCodeContext build(SourceMatch match, Instant openedAt) {
		List<GitHubSourceFile> files = new ArrayList<>();
		Map<String, GitHubCommit> commits = new LinkedHashMap<>();
		int totalLength = 0;

		for (String path : match.paths()) {
			List<GitHubCommitResponse> history = history(path, openedAt);
			if (history.isEmpty()) {
				// 그 시점 이전에 이 경로를 건드린 commit이 없다. Incident 당시에는 없던 파일이다.
				continue;
			}
			Optional<GitHubSourceFile> file = sourceFile(path, history.get(0).sha(), match.term());
			if (file.isEmpty()) {
				continue;
			}
			int length = file.get().snippet().length();
			if (totalLength + length > GitHubCodeContext.MAX_TOTAL_SNIPPET_LENGTH) {
				continue;
			}
			files.add(file.get());
			totalLength += length;
			collectRecentCommits(history, path, openedAt, commits);
		}

		GitHubCodeContext context = context(files, boundedCommits(commits));
		logger.info("GitHub 코드 맥락을 모았다. repository={} matchedFiles={} commits={}",
				this.properties.repositoryFullName(), context.files().size(), context.commits().size());
		return context;
	}

	/**
	 * Incident가 열리기 전에 이 경로를 건드린 commit을 최근 순으로 받아 온다.
	 * <p>
	 * {@code until}로 그 시점까지만 요청하고 받은 값도 다시 거른다. Incident 이후의 변경은 대개 그
	 * Incident를 고치려던 수정이며, 그것이 원인 후보에 섞이면 결과를 보고 원인을 되짚는 순환이 된다.
	 * 경계를 서버에만 맡기지 않는 것은 그 한 줄이 이 기능의 뜻을 정하기 때문이다.
	 */
	private List<GitHubCommitResponse> history(String path, Instant openedAt) {
		List<GitHubCommitResponse> commits = get((uriBuilder) -> uriBuilder
			.pathSegment("repos", this.properties.owner(), this.properties.repository(), "commits")
			.queryParam("path", path)
			.queryParam("sha", this.properties.ref())
			.queryParam("until", openedAt.toString())
			.queryParam("per_page", COMMIT_PAGE_SIZE)
			.build(), GitHubCommitResponse[].class).map(List::of).orElseGet(List::of);
		return commits.stream().filter((commit) -> committedAtOrBefore(commit, openedAt)).toList();
	}

	private static boolean committedAtOrBefore(GitHubCommitResponse commit, Instant openedAt) {
		Instant committedAt = committedAt(commit);
		return committedAt != null && !committedAt.isAfter(openedAt);
	}

	private static Instant committedAt(GitHubCommitResponse commit) {
		return (commit.commit() != null && commit.commit().committer() != null) ? commit.commit().committer().date()
				: null;
	}

	private void collectRecentCommits(List<GitHubCommitResponse> history, String path, Instant openedAt,
			Map<String, GitHubCommit> collected) {
		Instant from = openedAt.minus(RECENT_COMMIT_WINDOW);
		for (GitHubCommitResponse response : history) {
			Instant committedAt = committedAt(response);
			if (committedAt.isBefore(from)) {
				continue;
			}
			GitHubCommit commit = new GitHubCommit(response.sha(),
					GitHubCommit.boundedMessage(response.commit().message()), committedAt, List.of(path));
			collected.merge(commit.sha(), commit, (existing, added) -> existing.mergePaths(added.paths()));
		}
	}

	/** 최근 순으로 정렬해 상한까지만 남긴다. 파일별로 모으면 순서가 파일 순서를 따라가 뒤섞인다. */
	private static List<GitHubCommit> boundedCommits(Map<String, GitHubCommit> commits) {
		return commits.values()
			.stream()
			.sorted(Comparator.comparing(GitHubCommit::committedAt).reversed().thenComparing(GitHubCommit::sha))
			.limit(GitHubCodeContext.MAX_COMMITS)
			.toList();
	}

	/**
	 * 그 시점의 마지막 commit에서 파일 내용을 읽어 조각을 만든다.
	 * <p>
	 * 기본 branch의 현재 내용을 읽지 않는다. 그러면 Incident 이후에 이미 고쳐진 코드를 보고 그 Incident의
	 * 원인을 찾는 셈이 된다. commit SHA를 ref로 주면 그 시점의 판을 정확히 가리킬 수 있다.
	 * <p>
	 * 그 commit이 파일을 지웠거나 내용이 너무 커서 본문이 오지 않으면 이 파일만 건너뛴다. 맥락 전체를
	 * 실패로 만들면 나머지 파일의 근거까지 함께 잃는다.
	 */
	private Optional<GitHubSourceFile> sourceFile(String path, String revisionSha, String term) {
		return get((uriBuilder) -> uriBuilder
			.pathSegment("repos", this.properties.owner(), this.properties.repository(), "contents")
			.pathSegment(path.split("/"))
			.queryParam("ref", revisionSha)
			.build(), GitHubFileContentResponse.class).flatMap(GitHubFileContentResponse::decodedContent)
			.flatMap((content) -> GitHubSourceFile.of(path, revisionSha, content, term));
	}

	private GitHubCodeContext context(List<GitHubSourceFile> files, List<GitHubCommit> commits) {
		return new GitHubCodeContext(this.properties.repositoryFullName(), this.properties.ref(),
				this.properties.sourceRoot(), files, commits);
	}

	/**
	 * 요청을 보내고 본문을 읽는다. 없는 것은 비어 있는 결과로, 나머지 실패는 예외로 나온다.
	 * <p>
	 * 상태 코드를 직접 다룬다. 기본 동작에 맡기면 오류에서 나는 예외의 message에 응답 본문이 담기고,
	 * 그것이 log나 상위 예외로 흘러가면 내보내지 않기로 한 값이 남는다. 여기서 가로채면 본문을 한 번도
	 * 읽지 않은 채로 실패를 만들 수 있다.
	 * <p>
	 * 404만 실패로 만들지 않는다. 지워진 파일이나 이력이 없는 경로에서 정상적으로 나오는 답이며, 그때
	 * 할 일은 그 파일을 빼고 계속하는 것이다. 설정이 없는 repository를 가리켜 모든 호출이 404가 되는
	 * 경우에도 결과는 "찾은 것이 없는 맥락"이 되어, 사용자에게는 관련 코드를 찾지 못한 것과 같은
	 * 사실로 보인다. 무엇이 실제로 잘못됐는지는 server log가 나눈다.
	 */
	private <T> Optional<T> get(Function<UriBuilder, URI> uri, Class<T> responseType) {
		try {
			return Optional.ofNullable(
					this.restClient.get().uri(uri).retrieve().onStatus(HttpStatusCode::isError, this::failed)
						.body(responseType));
		}
		catch (MissingGitHubResourceException ex) {
			return Optional.empty();
		}
		catch (ResourceAccessException ex) {
			// 연결하지 못했거나 제한 시간 안에 응답이 오지 않았다. 요청이 실제로 처리되었을 가능성도
			// 있다고 보고 다시 부르지 않는다.
			logger.warn("GitHub API에 닿지 못했다. reason={}", ex.getClass().getSimpleName());
			throw new IncidentGitHubContextUnavailableException("provider_unreachable", ex);
		}
		catch (RestClientException ex) {
			logger.warn("GitHub API 호출에 실패했다. reason={}", ex.getClass().getSimpleName());
			throw new IncidentGitHubContextUnavailableException("provider_call_failed", ex);
		}
	}

	/**
	 * 오류 응답을 실패로 옮긴다. 본문은 읽지 않는다.
	 * <p>
	 * 한도 초과를 따로 알아본다. GitHub은 한도를 넘겼을 때 403이나 429로 답하는데, 403만 보고는 권한이
	 * 없는 것인지 한도를 넘긴 것인지 구분되지 않는다. 남은 횟수 header가 그 둘을 갈라 주며, 운영자가
	 * 봐야 할 다음 행동이 서로 다르다. 어느 쪽이든 여기서 다시 부르지는 않는다.
	 */
	private void failed(HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		if (status == HttpStatus.NOT_FOUND.value()) {
			throw new MissingGitHubResourceException();
		}
		if (status == HttpStatus.TOO_MANY_REQUESTS.value() || rateLimited(response)) {
			logger.warn("GitHub API 호출 한도를 넘겼다. status={}", status);
			throw new IncidentGitHubContextUnavailableException("provider_rate_limited", null);
		}
		logger.warn("GitHub API가 오류로 응답했다. status={}", status);
		throw new IncidentGitHubContextUnavailableException("provider_http_" + status, null);
	}

	private static boolean rateLimited(ClientHttpResponse response) {
		return RATE_LIMIT_EXHAUSTED.equals(response.getHeaders().getFirst(RATE_LIMIT_REMAINING_HEADER));
	}

	/** 어떤 검색어가 파일을 찾아냈는지와 그 결과이다. 조각을 자를 때 같은 검색어를 다시 쓴다. */
	private record SourceMatch(String term, List<String> paths) {
	}

	/**
	 * GitHub에 그 자원이 없다는 뜻이며 이 Class 밖으로 나가지 않는다. 밖으로 내보내면 부르는 쪽이
	 * "없음"과 "실패"를 구분해 처리해야 하는데, 여기서는 그 구분이 곧 비어 있는 결과이다.
	 */
	private static class MissingGitHubResourceException extends RuntimeException {

	}

}
