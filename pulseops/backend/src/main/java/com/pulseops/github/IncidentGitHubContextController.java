package com.pulseops.github;

import com.pulseops.github.dto.IncidentGitHubContextResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident 하나의 GitHub 코드 맥락을 모으고 읽는다.
 * <p>
 * Incident 아래의 경로에 둔다. 맥락은 Incident 없이 존재할 수 없고 언제나 Incident 하나를 가리키므로 그
 * 관계가 경로에 그대로 드러나야 한다.
 * <p>
 * Controller를 따로 둔다. AI 분석과 한 Class에 두면 외부 저장소를 읽는 일과 외부 model에 묻는 일이
 * 한자리에 섞이고, 사용자가 두 단계를 따로 통제한다는 설계가 경로에서만 보이고 코드에서는 사라진다.
 */
@RestController
@RequestMapping("/api/incidents/{incidentId}/github-context")
public class IncidentGitHubContextController {

	private final IncidentGitHubContextService incidentGitHubContextService;

	public IncidentGitHubContextController(IncidentGitHubContextService incidentGitHubContextService) {
		this.incidentGitHubContextService = incidentGitHubContextService;
	}

	@GetMapping
	public IncidentGitHubContextResponse getGitHubContext(@PathVariable Long incidentId) {
		return this.incidentGitHubContextService.context(incidentId);
	}

	/**
	 * 맥락을 모은다. 본문이 없다.
	 * <p>
	 * repository나 branch, 검색어를 받는 자리를 두지 않는다. 그 자리를 열면 이 Endpoint는 우리 token으로
	 * 아무 repository나 대신 읽어 주는 창구가 되고, 무엇을 봤는지가 요청마다 달라져 저장된 맥락이 무엇에
	 * 대한 것인지도 정해지지 않는다. 어느 Incident인지는 경로가, 어디를 볼지는 서버 설정이 정한다.
	 * <p>
	 * 새로 모았을 때와 이미 있을 때를 같은 200으로 답한다. 같은 요청을 몇 번 보내도 결과가 같고 Client가
	 * 두 경우에 다르게 할 일이 없다. Phase 9의 분석 생성과 같은 계약이다.
	 */
	@PostMapping
	public IncidentGitHubContextResponse createGitHubContext(@PathVariable Long incidentId) {
		return this.incidentGitHubContextService.collect(incidentId);
	}

}
