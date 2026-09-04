package com.pulseops.github;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * commit 목록을 열 하나에 JSON 배열로 담는다.
 * <p>
 * source 조각 목록과 같은 이유로 같은 방식을 쓴다. 두 변환기를 하나의 일반화된 JSON 변환기로 합치지
 * 않는 것은, 그렇게 하면 어떤 Type으로 되읽을지가 열의 선언이 아니라 실행 시점의 Type 정보에 달리게
 * 되기 때문이다. 열마다 담기는 것이 정해져 있는 편이 읽기도 쉽고 되읽기도 안전하다.
 */
@Converter
public class GitHubCommitListJsonConverter implements AttributeConverter<List<GitHubCommit>, String> {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final TypeReference<List<GitHubCommit>> COMMIT_LIST = new TypeReference<>() {
	};

	@Override
	public String convertToDatabaseColumn(List<GitHubCommit> attribute) {
		return MAPPER.writeValueAsString(attribute);
	}

	@Override
	public List<GitHubCommit> convertToEntityAttribute(String dbData) {
		return MAPPER.readValue(dbData, COMMIT_LIST);
	}

}
