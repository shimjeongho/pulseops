package com.pulseops.github;

/**
 * 지금은 GitHub 코드 맥락을 모을 수 없다는 뜻이다.
 * <p>
 * 기능이 꺼져 있는 경우와 인증에 실패한 경우, 설정된 repository나 ref가 없는 경우, 한도를 넘긴 경우,
 * GitHub가 오류를 낸 경우를 모두 이 하나로 모은다. Client가 그것들을 나눠 받아도 할 수 있는 일이 같기
 * 때문이다. 전부 "지금은 안 되니 나중에 다시"이며, 그 다음에 무엇을 보여 줄지는 실패의 종류가 아니라
 * 화면의 판단이다.
 * <p>
 * 잘못된 설정으로 없는 repository를 가리키고 있는 경우도 여기 포함된다. 그것은 운영자의 실수이지만
 * 사용자가 보는 사실은 똑같이 "코드 맥락을 쓸 수 없다"이고, 서버 설정 상태가 API 계약이 되면 설정을
 * 바꾸는 것만으로 Client가 보는 응답이 달라진다. 원인 구분은 server log가 한다.
 * <p>
 * {@code reason}은 log에만 쓰는 짧은 고정 문자열이다. 응답에는 나가지 않는다. GitHub가 돌려준 본문에는
 * 요청 내용이나 계정 사정이 담겨 올 수 있어 그것을 그대로 밖으로 보낼 수 없다.
 */
public class IncidentGitHubContextUnavailableException extends RuntimeException {

	private final String reason;

	public IncidentGitHubContextUnavailableException(String reason, Throwable cause) {
		super("Incident GitHub context unavailable: " + reason, cause);
		this.reason = reason;
	}

	public String getReason() {
		return this.reason;
	}

}
