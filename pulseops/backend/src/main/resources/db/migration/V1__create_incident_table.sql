-- Incident는 사람이 보고 관리하는 영구 기록이다. 실시간 Metric은 Redis가 담당하므로 여기에는
-- Incident와 그것을 연 창의 관측값만 남기고 Telemetry Event 원문은 저장하지 않는다.
--
-- trigger_로 시작하는 열은 Incident를 연 창의 관측값이며 생성 이후 바뀌지 않는다. severity가
-- 올라가도 그대로 두어 "열렸을 때 무엇을 봤는가"가 남는다.
CREATE TABLE incident (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    service                VARCHAR(60)   NOT NULL,
    method                 VARCHAR(10)   NOT NULL,
    endpoint               VARCHAR(255)  NOT NULL,
    type                   VARCHAR(20)   NOT NULL,
    severity               VARCHAR(10)   NOT NULL,
    status                 VARCHAR(15)   NOT NULL,
    opened_at              DATETIME(6)   NOT NULL,
    acknowledged_at        DATETIME(6)   NULL,
    resolved_at            DATETIME(6)   NULL,
    updated_at             DATETIME(6)   NOT NULL,
    trigger_window_start   DATETIME(6)   NOT NULL,
    trigger_window_end     DATETIME(6)   NOT NULL,
    trigger_request_count  BIGINT        NOT NULL,
    trigger_error_count    BIGINT        NOT NULL,
    trigger_error_rate     DECIMAL(5, 2) NOT NULL,
    trigger_p95_latency_ms BIGINT        NULL,
    -- 같은 service/method/endpoint/type에 처리 중인 Incident가 둘 이상 생기지 않게 한다.
    -- MySQL에는 조건부 UNIQUE Index가 없으므로, 처리 중일 때만 값이 생기고 RESOLVED면 NULL이
    -- 되는 열을 만들어 UNIQUE를 건다. UNIQUE Index는 NULL을 서로 다른 값으로 보므로 RESOLVED는
    -- 이력으로 얼마든지 쌓인다.
    --
    -- Application이 채우지 않고 다른 열에서 파생시킨다. 직접 채우면 identity나 status를 바꿀 때
    -- 두 값이 어긋날 수 있고, 그 어긋남이 곧 중복 Incident를 뜻한다.
    active_key             VARCHAR(350) GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('OPEN', 'ACKNOWLEDGED')
                THEN CONCAT(service, '|', method, '|', endpoint, '|', type)
        END
    ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_active UNIQUE (active_key),
    -- 한 계열의 처리 중인 Incident를 찾는 것이 Detection의 가장 잦은 조회이다.
    INDEX idx_incident_series (service, method, endpoint, type, status),
    CONSTRAINT ck_incident_trigger_error_rate CHECK (trigger_error_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_incident_trigger_request_count CHECK (trigger_request_count >= 0),
    CONSTRAINT ck_incident_trigger_error_count CHECK (trigger_error_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
