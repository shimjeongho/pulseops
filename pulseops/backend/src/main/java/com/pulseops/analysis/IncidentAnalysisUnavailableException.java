package com.pulseops.analysis;

/**
 * 지금은 AI 분석을 만들 수 없다는 뜻이다.
 * <p>
 * 기능이 꺼져 있는 경우와 provider 호출이 실패한 경우, 응답을 분석으로 쓸 수 없는 경우를 모두 이
 * 하나로 모은다. Client가 셋을 나눠 받아도 할 수 있는 일이 같기 때문이다. 셋 다 "지금은 안 되니
 * 나중에 다시"이며, 그 다음에 무엇을 보여 줄지는 실패의 종류가 아니라 화면의 판단이다. 종류마다 code를
 * 나누면 서버의 설정 상태가 API 계약이 되어, 기능을 켜고 끄는 것만으로 Client가 보는 응답이 바뀐다.
 * <p>
 * {@code reason}은 log에만 쓰는 짧은 고정 문자열이다. 응답에는 나가지 않는다. 무엇 때문에 실패했는지는
 * 운영자가 알아야 하지만, provider가 돌려준 문장에는 요청 내용이나 계정 사정이 담겨 올 수 있어 그것을
 * 그대로 밖으로 보낼 수 없다. 그래서 밖으로 나갈 수 있는 값만 이 자리에 남긴다.
 */
public class IncidentAnalysisUnavailableException extends RuntimeException {

	private final String reason;

	public IncidentAnalysisUnavailableException(String reason, Throwable cause) {
		super("Incident AI analysis unavailable: " + reason, cause);
		this.reason = reason;
	}

	public String getReason() {
		return this.reason;
	}

}
