package com.pulseops.github;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * Incident 하나를 위해 GitHub에서 한 번 모아 굳혀 둔 코드 맥락이다.
 * <p>
 * 만들어진 뒤에는 바뀌지 않는다. 상태를 바꾸는 method가 하나도 없는 것이 그 뜻이다. 이 기록은
 * {@code capturedAt} 시점의 repository에서 읽은 것이며, 이후 그 코드가 고쳐지거나 파일이 옮겨져도
 * 여기의 내용은 움직이지 않는다. 움직이게 만들면 어제 이 맥락을 근거로 만든 분석이 오늘은 존재하지도
 * 않는 코드를 인용하게 되고, 화면을 새로 고칠 때마다 GitHub의 분당 한도를 깎는다.
 * <p>
 * 다시 모으는 수단을 두지 않는다. 잘못 모았거나 설정이 바뀐 경우를 위한 갱신 경로를 열면 "이 맥락이
 * 무엇을 보고 만들어졌는가"에 답이 여러 개가 되고, 그 맥락으로 만든 분석이 무엇을 근거로 했는지도
 * 정해지지 않는다. 필요해질 때 그 부채를 갚는 편이, 지금 불변을 포기하는 것보다 싸다.
 * <p>
 * 여기 담긴 문자열은 전부 신뢰할 수 없는 데이터이다. source의 주석과 문자열, commit message는 우리가
 * 내용을 정하지 않으며 지시문처럼 보이는 문장이 들어 있을 수 있다. 그것이 지시가 되지 않게 하는 일은
 * prompt의 구조가 맡고, 이 Class는 그 값을 원문 그대로 보관하는 책임만 진다.
 * <p>
 * Incident를 연관으로 두지 않고 {@code incidentId}만 갖는다. 이 맥락은 Incident의 어떤 값도 읽지 않고,
 * 수집에 쓰인 값은 이미 굳어 있다. 없는 Incident를 가리킬 수 없다는 것은 DB의 FK가 지킨다.
 * <p>
 * Schema는 Flyway가 관리한다. Incident당 맥락이 하나뿐인 것은 {@code incident_id}의 UNIQUE가 최종적으로
 * 보장한다.
 */
@Entity
@Table(name = "incident_github_context")
public class IncidentGitHubContext {

	/**
	 * source 조각 목록을 담는 열의 상한이다.
	 * <p>
	 * Phase 9의 분석 결과보다 훨씬 크다. 그쪽은 문장 몇 개지만 이쪽은 코드 조각 셋을 담는다. Application이
	 * {@link GitHubCodeContext#MAX_TOTAL_SNIPPET_LENGTH}로 이미 막고 있으므로 이 값은 그 위의 마지막
	 * 그물이며, JSON 표기와 한글 주석이 섞인 최악의 경우까지 들어가도록 잡았다.
	 */
	static final int FILES_JSON_MAX_LENGTH = 65_535;

	/** commit 목록을 담는 열의 상한이다. 다섯 개의 sha와 message와 경로가 들어갈 크기이다. */
	static final int COMMITS_JSON_MAX_LENGTH = 4000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "incident_id", nullable = false)
	private Long incidentId;

	/** 설정된 {@code owner/repository}이다. 나중에 설정이 바뀌어도 이 맥락이 어디서 왔는지는 남는다. */
	@Column(name = "repository", nullable = false, length = 200)
	private String repository;

	/** 검색과 이력 조회의 기준이 된 branch이다. 파일 내용을 실제로 읽은 판은 조각마다 따로 남는다. */
	@Column(name = "ref", nullable = false, length = 100)
	private String ref;

	@Column(name = "source_root", nullable = false, length = 300)
	private String sourceRoot;

	@Convert(converter = GitHubSourceFileListJsonConverter.class)
	@Column(name = "files_json", nullable = false, length = FILES_JSON_MAX_LENGTH)
	private List<GitHubSourceFile> files;

	@Convert(converter = GitHubCommitListJsonConverter.class)
	@Column(name = "commits_json", nullable = false, length = COMMITS_JSON_MAX_LENGTH)
	private List<GitHubCommit> commits;

	@Column(name = "captured_at", nullable = false)
	private Instant capturedAt;

	protected IncidentGitHubContext() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 모아 온 맥락을 기록으로 굳힌다.
	 * <p>
	 * 값을 낱개로 받지 않고 {@link GitHubCodeContext}를 통째로 받는다. repository와 ref와 source root는
	 * 모두 문자열이라 순서가 뒤바뀌어도 compile되고, 그러면 ref 자리에 source root가 들어간 기록이 남는다.
	 * 개수와 크기 상한을 지켰는지는 그 record가 만들어질 때 이미 확인되었으므로 여기서 다시 검사하지 않는다.
	 * <p>
	 * {@code capturedAt}은 호출자가 넘긴다. 여기서 시계를 읽으면 그 시각을 테스트가 정할 수 없다.
	 */
	public static IncidentGitHubContext of(Long incidentId, GitHubCodeContext context, Instant capturedAt) {
		IncidentGitHubContext stored = new IncidentGitHubContext();
		stored.incidentId = incidentId;
		stored.repository = context.repository();
		stored.ref = context.ref();
		stored.sourceRoot = context.sourceRoot();
		stored.files = context.files();
		stored.commits = context.commits();
		stored.capturedAt = capturedAt;
		return stored;
	}

	/**
	 * 저장된 맥락을 다시 값으로 돌려준다. 코드 기반 분석은 GitHub이 아니라 이 기록에서 근거를 얻는다.
	 * <p>
	 * 이 method가 있기 때문에 분석을 몇 번 만들든 GitHub 호출은 늘지 않는다. 분석 쪽이 저장소를 직접
	 * 부르는 구조였다면 "이미 모아 둔 맥락으로 분석한다"는 약속이 코드 어디에도 나타나지 않는다.
	 */
	public GitHubCodeContext toCodeContext() {
		return new GitHubCodeContext(this.repository, this.ref, this.sourceRoot, this.files, this.commits);
	}

	public Long getId() {
		return this.id;
	}

	public Long getIncidentId() {
		return this.incidentId;
	}

	public String getRepository() {
		return this.repository;
	}

	public String getRef() {
		return this.ref;
	}

	public String getSourceRoot() {
		return this.sourceRoot;
	}

	public List<GitHubSourceFile> getFiles() {
		return this.files;
	}

	public List<GitHubCommit> getCommits() {
		return this.commits;
	}

	public Instant getCapturedAt() {
		return this.capturedAt;
	}

}
