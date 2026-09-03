-- Daily Incident Report는 완전히 끝난 하루의 Incident 운영 현황을 한 행으로 남긴 집계 결과이다.
--
-- Incident만 집계한다. 하루 총 요청 수나 응답 시간 분포는 Redis의 Rolling Metric에만 있고 그 Key의
-- 수명은 300초라, 하루가 끝난 뒤에는 어떤 방법으로도 되살릴 수 없다. 없는 값을 추정해 채우면
-- 정확해 보이지만 근거가 없는 숫자가 Report에 남는다.
--
-- Incident를 가리키는 열이 없다. 이 행은 그 날짜의 집계 결과이지 어떤 Incident의 소유자가 아니다.
-- 어떤 Incident가 세어졌는지 알아야 하면 period 안의 Incident를 다시 조회하면 되고, 그 목록을 여기에
-- 복사해 두면 원본이 바뀔 때 두 값이 어긋난다.
CREATE TABLE daily_incident_report (
    id                             BIGINT      NOT NULL AUTO_INCREMENT,
    -- 집계 대상 날짜이며 UTC 기준 달력 하루이다. 밖에서 이 Report를 가리키는 값도 이것이다.
    report_date                    DATE        NOT NULL,
    -- 집계 구간은 [period_start, period_end)이다. 두 값을 함께 남기는 것은 날짜만으로는 어느
    -- 시간대의 하루인지 알 수 없기 때문이며, 나중에 기준이 바뀌어도 이미 만든 Report가 무엇을 센
    -- 것인지는 이 두 값이 그대로 말해 준다.
    period_start                   DATETIME(6) NOT NULL,
    period_end                     DATETIME(6) NOT NULL,
    opened_count                   BIGINT      NOT NULL,
    error_rate_opened_count        BIGINT      NOT NULL,
    latency_opened_count           BIGINT      NOT NULL,
    acknowledged_count             BIGINT      NOT NULL,
    resolved_count                 BIGINT      NOT NULL,
    unresolved_at_period_end_count BIGINT      NOT NULL,
    -- 그 날짜에 해결된 Incident가 하나도 없으면 NULL이다. 0으로 두면 "즉시 해결됐다"와 "해결된 것이
    -- 없다"가 같은 값이 된다.
    average_resolution_seconds     BIGINT      NULL,
    max_resolution_seconds         BIGINT      NULL,
    generated_at                   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 날짜의 Report가 하나뿐인 것을 최종적으로 보장하는 자리이다. Application이 먼저 하는
    -- "있으면 건너뛴다" 검사는 두 실행이 겹치면 둘 다 없다고 볼 수 있어 그것만으로는 중복을 막지
    -- 못한다. 이 UNIQUE는 날짜로 하나를 찾는 조회와 최근 순 정렬에도 그대로 쓰이므로 같은 열로
    -- Index를 또 만들지 않는다.
    CONSTRAINT uk_daily_incident_report_date UNIQUE (report_date),
    CONSTRAINT ck_daily_incident_report_period CHECK (period_start < period_end),
    -- 의미 있는 검증은 Domain이 하고 여기에는 어떤 경로로도 넘을 수 없는 바닥만 둔다.
    CONSTRAINT ck_daily_incident_report_counts CHECK (
        opened_count >= 0
            AND error_rate_opened_count >= 0
            AND latency_opened_count >= 0
            AND acknowledged_count >= 0
            AND resolved_count >= 0
            AND unresolved_at_period_end_count >= 0
    ),
    CONSTRAINT ck_daily_incident_report_resolution_seconds CHECK (
        average_resolution_seconds >= 0 AND max_resolution_seconds >= 0
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
