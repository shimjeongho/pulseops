-- Detection State는 Incident 판정에 필요한 "직전까지 무엇을 봤는가"를 담는다.
--
-- 이상은 창 하나로 단정하지 않고 연속된 창으로 확인한다. 그러려면 앞 창의 판정을 기억해야 하는데,
-- 이 기억이 Redis에 있으면 TTL이나 재시작으로 사라져 진행 중이던 연속이 조용히 끊긴다. 그때 열리지
-- 않은 Incident는 사후에 복구할 방법이 없으므로 MySQL에 둔다.
--
-- 계열 하나에 종류마다 한 행이다. 오류율과 응답 시간은 나빠지는 시점도 회복되는 시점도 서로 달라
-- 연속 횟수를 함께 셀 수 없다.
CREATE TABLE detection_state (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    service                   VARCHAR(60)  NOT NULL,
    method                    VARCHAR(10)  NOT NULL,
    endpoint                  VARCHAR(255) NOT NULL,
    type                      VARCHAR(20)  NOT NULL,
    -- 여기까지 평가했다는 표시이며 같은 창을 두 번 반영하지 않기 위한 기준이다. Kafka는
    -- at-least-once라 같은 구간이 다시 처리될 수 있고, 그때 연속 횟수가 두 번 올라가면 한 번만
    -- 나빴던 창으로 Incident가 열린다.
    last_evaluated_window_end DATETIME(6)  NOT NULL,
    abnormal_consecutive      INT          NOT NULL DEFAULT 0,
    recovery_consecutive      INT          NOT NULL DEFAULT 0,
    -- 벽시계가 아니라 마지막으로 반영한 창의 끝이다. 두 값이 섞이면 "언제 평가했는가"와 "어느
    -- 구간을 평가했는가"를 구분할 수 없다.
    updated_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 계열과 종류마다 상태는 정확히 하나다. 둘로 갈라지면 연속 횟수가 나뉘어 어느 쪽도 기준에
    -- 도달하지 못한다. 이 UNIQUE는 조회용 index를 겸하므로 같은 열로 index를 또 만들지 않는다.
    CONSTRAINT uk_detection_state_series_type UNIQUE (service, method, endpoint, type),
    CONSTRAINT ck_detection_state_abnormal_consecutive CHECK (abnormal_consecutive >= 0),
    CONSTRAINT ck_detection_state_recovery_consecutive CHECK (recovery_consecutive >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
