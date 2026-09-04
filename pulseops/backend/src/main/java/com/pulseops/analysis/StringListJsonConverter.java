package com.pulseops.analysis;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문자열 목록을 열 하나에 JSON 배열로 담는다.
 * <p>
 * AI 분석의 근거, 권장 조치, 한계는 모두 짧은 문장 몇 개이고 언제나 통째로 읽힌다. 목록 안을 조건으로
 * 찾을 일이 없으므로 table을 따로 만들 이유가 없고, 셋을 위해 table을 셋 만들면 분석 하나를 읽는 데
 * join이 셋 붙는다.
 * <p>
 * ObjectMapper를 주입받지 않고 여기서 만든다. 이 Class를 만드는 것은 Spring이 아니라 Hibernate이고,
 * 담는 값이 문자열 목록뿐이라 Application의 Jackson 설정에 기댈 것이 없다. 오히려 그 설정이 바뀌었을 때
 * 이미 저장된 열을 읽지 못하게 되는 편이 위험하다. 이 열의 형식은 Application 설정과 무관하게 고정이다.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	@Override
	public String convertToDatabaseColumn(List<String> attribute) {
		return MAPPER.writeValueAsString(attribute);
	}

	@Override
	public List<String> convertToEntityAttribute(String dbData) {
		return MAPPER.readValue(dbData, STRING_LIST);
	}

}
