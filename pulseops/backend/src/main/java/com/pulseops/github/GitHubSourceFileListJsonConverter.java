package com.pulseops.github;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * source 조각 목록을 열 하나에 JSON 배열로 담는다.
 * <p>
 * Phase 9의 문자열 목록 변환과 같은 방식이다. 이 목록은 언제나 통째로 읽히고 목록 안을 조건으로 찾을
 * 일이 없으므로 table을 따로 만들면 맥락 하나를 읽는 데 join만 붙는다.
 * <p>
 * ObjectMapper를 주입받지 않고 여기서 만든다. 이 Class를 만드는 것은 Spring이 아니라 Hibernate이고,
 * Application의 Jackson 설정이 바뀌었을 때 이미 저장된 열을 읽지 못하게 되는 편이 더 위험하다. 이 열의
 * 형식은 Application 설정과 무관하게 고정이다.
 */
@Converter
public class GitHubSourceFileListJsonConverter implements AttributeConverter<List<GitHubSourceFile>, String> {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final TypeReference<List<GitHubSourceFile>> SOURCE_FILE_LIST = new TypeReference<>() {
	};

	@Override
	public String convertToDatabaseColumn(List<GitHubSourceFile> attribute) {
		return MAPPER.writeValueAsString(attribute);
	}

	@Override
	public List<GitHubSourceFile> convertToEntityAttribute(String dbData) {
		return MAPPER.readValue(dbData, SOURCE_FILE_LIST);
	}

}
