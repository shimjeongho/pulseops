package com.pulseops.github.dto;

import com.pulseops.github.GitHubCommit;
import com.pulseops.github.GitHubSourceFile;
import com.pulseops.github.IncidentGitHubContext;
import java.time.Instant;
import java.util.List;

/**
 * Incident 하나의 GitHub 코드 맥락을 Client가 읽는 형태이다.
 * <p>
 * Entity를 그대로 내보내지 않는다. 저장에 필요한 값과 화면에 필요한 값이 다르고, Entity를 내보내면
 * 저장 구조를 바꾸는 것이 곧 API 계약을 바꾸는 일이 된다. GitHub 응답 DTO는 더더욱 내보내지 않는다.
 * 그 모양은 남이 정한 것이라 우리 Client가 거기에 묶이면 안 된다.
 * <p>
 * source 조각 본문을 담지 않는다. 화면이 코드 편집기가 아니고, 조각 하나가 수천 글자라 세 개만 실려도
 * 상세 화면의 다른 내용이 전부 밀려난다. 사람이 여기서 알아야 할 것은 "어느 파일의 어느 판을 근거로
 * 삼았는가"이며, 그것은 경로와 revision만으로 충분하고 실제 코드는 GitHub에서 보는 편이 낫다.
 * <p>
 * token은 어디에도 없다. 이 record가 담는 값 중 설정에서 온 것은 repository와 ref와 source root뿐이며
 * 셋 다 공개되어도 무방한 값이다.
 * <p>
 * {@code capturedAt}을 담는다. 이 맥락은 그 시점의 repository를 보고 만든 것이고 이후 바뀌지 않으므로,
 * 그 시각은 "이 맥락이 무엇을 알 수 있었는가"의 상한이다.
 */
public record IncidentGitHubContextResponse(Long incidentId, String repository, String ref, String sourceRoot,
		List<SourceFile> files, List<Commit> commits, Instant capturedAt) {

	/** 근거로 삼은 파일 하나이다. {@code truncated}는 조각이 상한에 걸려 잘렸다는 표시이다. */
	public record SourceFile(String path, String revisionSha, boolean truncated) {

		static SourceFile from(GitHubSourceFile file) {
			return new SourceFile(file.path(), file.revisionSha(), file.truncated());
		}

	}

	/**
	 * Incident 전에 그 파일을 건드린 commit 하나이다. 작성자가 없는 것은 자리를 아껴서가 아니라, 이
	 * 기능이 누가 바꿨는지를 묻지 않기 때문이다.
	 */
	public record Commit(String sha, String message, Instant committedAt, List<String> paths) {

		static Commit from(GitHubCommit commit) {
			return new Commit(commit.sha(), commit.message(), commit.committedAt(), commit.paths());
		}

	}

	public static IncidentGitHubContextResponse from(IncidentGitHubContext context) {
		return new IncidentGitHubContextResponse(context.getIncidentId(), context.getRepository(), context.getRef(),
				context.getSourceRoot(), context.getFiles().stream().map(SourceFile::from).toList(),
				context.getCommits().stream().map(Commit::from).toList(), context.getCapturedAt());
	}

}
