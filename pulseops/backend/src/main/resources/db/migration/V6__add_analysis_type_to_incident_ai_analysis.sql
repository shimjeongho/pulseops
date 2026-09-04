-- AI 분석에 종류를 붙여 Incident 하나가 두 가지 분석을 가질 수 있게 한다.
--
-- 두 분석의 근거가 다르기 때문에 나눈다. INCIDENT_ONLY는 저장된 판정 근거만 보고 만든 것이고,
-- GITHUB_ENRICHED는 거기에 GitHub에서 모아 온 소스 조각과 최근 commit을 더해 만든 것이다. 같은 Incident의
-- 두 분석이 서로 다른 것을 보고 만들어졌으므로, 어느 쪽을 읽고 있는지 모르면 결론의 무게도 알 수 없다.
--
-- 기존 행을 지우거나 고치지 않는다. 이미 만들어진 분석의 summary도 model도 prompt 판도 snapshot도 그대로
-- 두고, 종류만 INCIDENT_ONLY로 채운다. 그 행들은 실제로 Incident 근거만 보고 만들어졌으므로 이 값은
-- 새로 정하는 사실이 아니라 이미 참인 사실을 적는 것이다.
--
-- 순서를 지킨다. 값을 채우기 전에 NOT NULL을 걸면 기존 행이 걸려 migration이 실패하고, UNIQUE를 먼저
-- 바꾸면 그 사이 같은 Incident에 두 행이 들어올 수 있다.
ALTER TABLE incident_ai_analysis
    ADD COLUMN analysis_type VARCHAR(20) NULL AFTER incident_id;

UPDATE incident_ai_analysis
SET analysis_type = 'INCIDENT_ONLY'
WHERE analysis_type IS NULL;

ALTER TABLE incident_ai_analysis
    MODIFY COLUMN analysis_type VARCHAR(20) NOT NULL;

-- Incident당 하나였던 제약을 Incident와 종류의 조합으로 바꾼다. 이것을 바꾸지 않으면 코드 기반 분석은
-- 두 번째 행으로 저장될 수 없고, 그렇다고 기존 행을 덮어쓰면 근거가 다른 두 분석 중 하나가 사라진다.
--
-- 여전히 UNIQUE인 것이 중요하다. 종류마다 하나씩만 남기는 자리가 여기이며, Application이 먼저 하는
-- "이미 있으면 다시 부르지 않는다" 검사는 두 요청이 겹치면 둘 다 없다고 볼 수 있어 그것만으로는 중복을
-- 막지 못한다. 이 UNIQUE는 종류로 하나를 찾는 조회에도 그대로 쓰이므로 같은 열로 Index를 또 만들지 않는다.
ALTER TABLE incident_ai_analysis
    DROP INDEX uk_incident_ai_analysis_incident,
    ADD CONSTRAINT uk_incident_ai_analysis_incident_type UNIQUE (incident_id, analysis_type);

-- 코드 기반 분석이 실제로 어떤 맥락을 보고 만들어졌는지를 가리킨다.
--
-- inputSnapshot에 소스 조각을 밀어 넣지 않는다. 그 열은 Incident 사실을 담는 자리이고 크기도 그만큼이며,
-- 무엇보다 같은 맥락을 두 곳에 복사해 두면 언젠가 두 값이 서로 다른 것을 가리킨다. 맥락의 원본은
-- incident_github_context이고 이 열은 그 행을 가리키기만 한다.
--
-- NULL을 허용하는 것은 INCIDENT_ONLY 분석에는 맥락이 없기 때문이다. GITHUB_ENRICHED에는 반드시 있어야
-- 하지만 그 규칙은 종류에 따라 달라지므로 DB의 열 제약으로는 표현할 수 없고, 분석을 만드는 자리에서
-- 강제한다.
ALTER TABLE incident_ai_analysis
    ADD COLUMN github_context_id BIGINT NULL,
    ADD CONSTRAINT fk_incident_ai_analysis_github_context
        FOREIGN KEY (github_context_id) REFERENCES incident_github_context (id);
