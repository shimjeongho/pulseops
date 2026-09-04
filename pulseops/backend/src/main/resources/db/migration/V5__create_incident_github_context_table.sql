-- Incident 하나를 위해 GitHub에서 한 번 모아 굳혀 둔 코드 맥락이다.
--
-- 이 행은 "Incident 발생 당시의 repository"가 아니라 "그 시점을 기준으로 우리가 찾아낸 몇 개의 조각"이다.
-- 관련 file은 endpoint 문자열 검색으로 고르고 그 검색은 기본 branch만 본다. 그때는 있었지만 지금은
-- 지워졌거나 이름이 바뀐 file은 애초에 후보에도 오르지 못하므로, 이 표를 완전한 복원으로 읽으면 안 된다.
--
-- 한 번 저장되면 바뀌지 않는다. 갱신 경로를 두지 않는 것은, 그것을 열면 이 맥락으로 만든 분석이 무엇을
-- 근거로 했는지 답이 여러 개가 되기 때문이다. GitHub이 이후 바뀌어도 여기 담긴 내용은 그대로이며 그것이
-- 의도이다.
--
-- Incident table에 열을 더하지 않고 별도 table로 둔다. Incident는 판정이 열고 사람이 닫는 관측 기록이고,
-- 이것은 사람이 요청했을 때만 한 번 만들어지는 외부 저장소의 사본이다. 같은 행에 섞으면 관측된 사실과
-- 밖에서 긁어 온 텍스트가 한 줄에 나란히 앉는다.
CREATE TABLE incident_github_context (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    -- 어느 Incident의 맥락인지이다. UNIQUE가 Incident당 맥락을 하나로 고정한다. Application이 먼저 하는
    -- "이미 있으면 다시 부르지 않는다" 검사는 두 요청이 겹치면 둘 다 없다고 볼 수 있어 그것만으로는
    -- 중복을 막지 못한다. 마지막으로 막는 자리는 언제나 이 UNIQUE이다.
    incident_id BIGINT       NOT NULL,
    -- 수집에 실제로 쓴 owner/repository이다. 나중에 설정이 바뀌어도 이 맥락이 어디서 왔는지는 남는다.
    repository  VARCHAR(200) NOT NULL,
    -- 검색과 이력 조회의 기준이 된 branch이다. 파일 내용을 실제로 읽은 판은 branch가 아니라 조각마다
    -- 담긴 revision SHA가 가리킨다. "main을 봤다"만 남기면 같은 경로를 다시 읽었을 때 그때와 같은
    -- 내용인지 말할 수 없다.
    ref         VARCHAR(100) NOT NULL,
    -- 검색과 선택의 1차 안전 경계이다. 이 경로 밖의 파일은 설정이든 script든 전부 제외되었다.
    source_root VARCHAR(300) NOT NULL,
    -- source 조각 목록이며 JSON 배열 한 줄로 담는다. 목록 안을 조건으로 검색할 일이 없고 언제나 통째로
    -- 읽히므로 table을 따로 만들면 join만 붙는다.
    --
    -- TEXT를 쓰는 것은 코드 조각 셋이 들어가기 때문이다. 담기는 양은 Application이 조각마다 그리고
    -- 합계로 이미 막고 있으며, 이 열의 크기는 그 위의 마지막 그물이다. 한글 주석이 섞인 최악의 경우까지
    -- 들어가면서도 무한정 커지지는 않는 크기이다.
    files_json  TEXT         NOT NULL,
    -- Incident 직전에 그 파일들을 건드린 commit 목록이다. 작성자를 담지 않는다. 이 기능은 누가 바꿨는지를
    -- 묻지 않으며, 이름과 email은 원인 추정에 아무 정보도 더하지 않으면서 개인을 가리킨다.
    --
    -- 여기 있는 것은 시간 순서뿐이다. 먼저 일어난 일이 원인이라는 뜻이 아니다.
    commits_json VARCHAR(4000) NOT NULL,
    captured_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_github_context_incident UNIQUE (incident_id),
    -- 맥락은 Incident에 종속된 기록이므로 없는 Incident를 가리킬 수 없게 한다. cascade를 걸지 않는 것은
    -- Incident를 지우는 기능이 없기 때문이다. 쓰이지 않는 삭제 규칙을 미리 정해 두면, 나중에 삭제가
    -- 생겼을 때 아무도 의도한 적 없는 연쇄 삭제가 이미 정해져 있는 셈이 된다.
    CONSTRAINT fk_incident_github_context_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
