-- Incident 하나에 대해 Claude가 사후에 만든 분석 결과이다.
--
-- Incident table에 열을 더하지 않고 별도 table로 둔다. 이 기록의 수명은 Incident와 다르다. Incident는
-- 판정이 열고 사람이 닫는 관측 기록이고, 이것은 사람이 요청했을 때만 한 번 만들어지는 추론 결과이다.
-- 같은 행에 섞으면 판정 근거와 AI가 지어낸 문장이 한 줄에 나란히 앉아, 어느 것이 관측이고 어느 것이
-- 가설인지 Schema만 보고는 말할 수 없게 된다.
--
-- 모든 문자열 열이 VARCHAR이다. TEXT를 쓰지 않는 것은 Hibernate의 ddl-auto=validate가 String field를
-- VARCHAR로 기대하기 때문이며, 길이 상한을 Schema에 적어 두면 Application이 거르지 못한 과도한 응답을
-- DB가 한 번 더 막는다. 길이는 Domain이 검증하는 상한과 같은 값이어야 한다.
CREATE TABLE incident_ai_analysis (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    -- 어느 Incident의 분석인지이다. UNIQUE가 Incident당 성공한 분석을 하나로 고정한다. Application이
    -- 먼저 하는 "이미 있으면 다시 부르지 않는다" 검사는 두 요청이 겹치면 둘 다 없다고 볼 수 있어
    -- 그것만으로는 중복을 막지 못한다. 마지막으로 막는 자리는 언제나 이 UNIQUE이다.
    incident_id         BIGINT       NOT NULL,
    -- 이 분석을 실제로 만든 model이다. 설정의 model 이름이 아니라 응답이 알려 준 값을 넣는다. 설정에는
    -- alias가 들어갈 수 있어, 나중에 어떤 model의 결과인지 물으면 설정값은 답하지 못한다.
    model               VARCHAR(100) NOT NULL,
    -- 이 분석을 만든 prompt의 판이다. prompt가 바뀌면 같은 Incident라도 다른 성격의 분석이 나오므로,
    -- 어떤 지시로 만들어진 결과인지가 남지 않으면 두 분석을 나란히 놓고 비교할 수 없다.
    prompt_version      VARCHAR(50)  NOT NULL,
    -- 분석 당시 Claude에게 실제로 보낸 Incident 사실이다. Incident는 분석 뒤에도 확인되거나 해결되고
    -- 심각도가 오를 수 있어, 지금의 Incident 행을 근거처럼 읽으면 분석이 보지 않은 값을 보고 만든 것처럼
    -- 보인다. Entity 전체를 덤프하지 않고 보낸 것만 그대로 남긴다.
    input_snapshot      VARCHAR(2000) NOT NULL,
    summary             VARCHAR(2000) NOT NULL,
    -- 가장 그럴듯한 원인 "가설"이다. 확정된 Root Cause가 아니며 열 이름도 그렇게 부르지 않는다.
    primary_hypothesis  VARCHAR(1000) NOT NULL,
    confidence          VARCHAR(10)  NOT NULL,
    -- 아래 셋은 문자열 목록이며 JSON 배열 한 줄로 담는다. 목록마다 table을 따로 만들면 분석 하나를
    -- 읽는 데 join이 셋 붙는데, 이 값들은 목록 안을 조건으로 검색할 일이 없고 언제나 통째로 읽힌다.
    evidence            VARCHAR(2500) NOT NULL,
    recommended_actions VARCHAR(2500) NOT NULL,
    limitations         VARCHAR(2500) NOT NULL,
    generated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_ai_analysis_incident UNIQUE (incident_id),
    -- 분석은 Incident에 종속된 기록이므로 없는 Incident를 가리킬 수 없게 한다. cascade를 걸지 않는 것은
    -- Incident를 지우는 기능이 없기 때문이다. 쓰이지 않는 삭제 규칙을 미리 정해 두면, 나중에 삭제가
    -- 생겼을 때 아무도 의도한 적 없는 연쇄 삭제가 이미 정해져 있는 셈이 된다.
    CONSTRAINT fk_incident_ai_analysis_incident FOREIGN KEY (incident_id) REFERENCES incident (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
