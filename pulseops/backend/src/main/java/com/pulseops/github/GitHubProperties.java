package com.pulseops.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 연동 설정이다.
 * <p>
 * 기본은 꺼져 있다. 이 기능이 없어도 Incident 탐지, Metric, 확인 처리, SSE, Daily Report, 기존 AI 분석은
 * 전부 그대로 동작한다. 켜짐이 기본이면 credential이 없는 환경에서 기동이나 테스트가 실패하거나, 아무도
 * 의도하지 않은 요청이 남의 rate limit을 깎는다.
 * <p>
 * token은 환경 변수에서만 온다. {@code application.properties}에도 실제 값이 아니라 환경 변수 참조만
 * 적는다. 설정 파일에 적힌 token은 그 순간 repository에 들어갈 수 있는 값이 되고, 한 번 들어가면
 * history에서 지울 수 없다. 필요한 권한은 읽기뿐이며 이 기능은 GitHub에 아무것도 쓰지 않는다.
 * <p>
 * owner와 repository를 서버가 정한다. 요청 본문으로 받지 않는 이유는, 그 자리를 열면 이 Endpoint가
 * 우리 token으로 임의의 repository를 대신 읽어 주는 창구가 되기 때문이다. 무엇을 관측하고 있는지는
 * 운영자가 아는 사실이지 요청마다 달라질 값이 아니다.
 * <p>
 * sourceRoot는 Demo Shop의 실제 source 경로처럼 관측 대상의 application 코드가 있는 곳 하나를 가리킨다.
 * 이 값이 검색과 선택의 1차 안전 경계이다. 여기를 벗어난 경로는 설정 파일이든 script든 전부 제외되므로,
 * repository 전체를 무차별로 외부 model에 보내는 일이 구조적으로 생기지 않는다.
 * <p>
 * base URL과 timeout은 설정으로 열지 않는다. 공식 endpoint는 하나이고 timeout은 이 Application이
 * 감당할 수 있는 대기 시간이라 운영자가 요청마다 다르게 정할 값이 아니다.
 */
@ConfigurationProperties(prefix = "pulseops.github")
public record GitHubProperties(boolean enabled, String token, String owner, String repository, String ref,
		String sourceRoot) {

	/** 검색과 조회에 쓰는 {@code owner/repository} 형태이다. 저장된 context에도 이 형태로 남는다. */
	public String repositoryFullName() {
		return this.owner + "/" + this.repository;
	}

}
