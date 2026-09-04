package com.pulseops.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * GitHub Contents API가 파일 하나에 대해 돌려주는 응답 중 이 기능이 쓰는 부분이다.
 * <p>
 * {@code encoding}을 함께 읽는다. 1MB가 넘는 파일에서는 {@code content}가 빈 문자열이고 encoding이
 * {@code none}으로 오는데, 그것을 그대로 복호화하면 빈 파일을 정상적으로 읽은 것처럼 다루게 된다.
 * 그런 파일은 조각을 만들지 못하는 것이 맞다.
 * <p>
 * {@code sha}는 이 내용의 blob SHA이며 어느 판을 읽었는지를 가리키는 유일한 값이다. 저장되는 맥락에
 * 그대로 남는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubFileContentResponse(String path, String sha, String content, String encoding, long size) {

	private static final String BASE64 = "base64";

	/**
	 * 본문을 문자열로 되돌린다. base64가 아니거나 값이 없으면 비어 있는 결과를 준다.
	 * <p>
	 * GitHub은 base64를 줄바꿈이 섞인 형태로 보내므로 MIME decoder를 쓴다. 기본 decoder는 줄바꿈을 만나면
	 * 실패한다.
	 */
	public Optional<String> decodedContent() {
		if (!BASE64.equals(this.encoding) || this.content == null || this.content.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(new String(Base64.getMimeDecoder().decode(this.content), StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
