package com.pulseops.analysis;

import com.pulseops.analysis.dto.IncidentAiAnalysisResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident 하나의 AI 분석을 만들고 읽는다.
 * <p>
 * Incident 아래의 경로에 둔다. 분석은 Incident 없이 존재할 수 없고 언제나 Incident 하나를 가리키므로 그
 * 관계가 경로에 그대로 드러나야 한다. 별도의 최상위 자원으로 두면 어느 Incident의 분석인지가 경로가
 * 아니라 응답 본문에만 남는다.
 * <p>
 * Controller를 따로 둔다. {@code IncidentController}에 붙이면 결정론적 판정 기록을 다루는 곳과 외부
 * 모델의 추론을 다루는 곳이 한 Class가 되고, 앞으로 둘 중 하나만 바뀌어도 다른 쪽 테스트가 함께 흔들린다.
 */
@RestController
@RequestMapping("/api/incidents/{incidentId}/analysis")
public class IncidentAiAnalysisController {

	private final IncidentAiAnalysisService incidentAiAnalysisService;

	public IncidentAiAnalysisController(IncidentAiAnalysisService incidentAiAnalysisService) {
		this.incidentAiAnalysisService = incidentAiAnalysisService;
	}

	@GetMapping
	public IncidentAiAnalysisResponse getAnalysis(@PathVariable Long incidentId) {
		return this.incidentAiAnalysisService.analysis(incidentId);
	}

	/**
	 * 분석을 만든다. 본문이 없다.
	 * <p>
	 * 사용자가 보낸 문장을 모델에 전달하는 자리를 두지 않는다. 그 자리를 열면 이 Endpoint는 Incident
	 * 분석기가 아니라 서버가 대신 요금을 내주는 자유 질문 창구가 되고, 무엇을 물었는지에 따라 답이
	 * 달라지므로 저장된 분석이 무엇에 대한 답인지도 정해지지 않는다. 무엇을 분석할지는 경로가, 어떻게
	 * 분석할지는 서버가 정한다.
	 * <p>
	 * 새로 만들었을 때와 이미 있을 때를 같은 200으로 답한다. 같은 요청을 몇 번 보내도 결과가 같고
	 * Client가 두 경우에 다르게 할 일이 없다. 상태 코드를 나누면 화면은 그것으로 분기하지 않으면서도
	 * 두 값을 모두 성공으로 처리해야 한다.
	 */
	@PostMapping
	public IncidentAiAnalysisResponse createAnalysis(@PathVariable Long incidentId) {
		return this.incidentAiAnalysisService.analyze(incidentId);
	}

	/**
	 * 코드 맥락까지 본 분석을 읽는다.
	 * <p>
	 * 기본 분석과 경로를 나눈다. 두 분석은 같은 모양의 결과를 돌려주지만 서로 다른 것을 보고 만들어졌고,
	 * 화면은 그 둘을 나란히 놓고 근거의 차이를 보여 준다. 한 경로에서 질의 값으로 종류를 고르게 하면
	 * 값을 빠뜨린 요청이 어느 쪽을 뜻하는지 정해지지 않고, 무엇이 있고 없는지도 응답 본문을 열어 봐야
	 * 알 수 있다.
	 */
	@GetMapping("/github")
	public IncidentAiAnalysisResponse getCodeAnalysis(@PathVariable Long incidentId) {
		return this.incidentAiAnalysisService.codeAnalysis(incidentId);
	}

	/**
	 * 코드 맥락까지 본 분석을 만든다. 본문이 없다.
	 * <p>
	 * 이 요청은 GitHub을 부르지 않는다. 근거로 쓸 맥락은 이미 수집되어 저장되어 있어야 하며, 없으면
	 * 만들지 않고 거절한다. 한 번의 요청으로 외부 저장소와 외부 model을 연달아 부르면 사용자는 그 둘을
	 * 따로 통제할 수 없고, 어느 쪽이 실패했는지도 하나의 오류로 뭉뚱그려진다.
	 * <p>
	 * 새로 만들었을 때와 이미 있을 때를 같은 200으로 답하는 것은 기본 분석과 같다.
	 */
	@PostMapping("/github")
	public IncidentAiAnalysisResponse createCodeAnalysis(@PathVariable Long incidentId) {
		return this.incidentAiAnalysisService.analyzeWithCode(incidentId);
	}

}
