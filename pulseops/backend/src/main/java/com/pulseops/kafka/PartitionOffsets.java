package com.pulseops.kafka;

/**
 * Kafka가 한 partition에 대해 알려 준 offset 두 개이다. lag은 여기에 담기지 않는다.
 * <p>
 * Broker에게 물어본 사실과 그 사실로 계산한 값을 나눈다. 계산을 Client 안에 넣으면 lag 공식이 Kafka에
 * 붙어 있게 되어, 경계값을 확인하려면 매번 Broker가 필요해진다.
 *
 * @param partition partition 번호
 * @param logEndOffset 이 partition에 다음으로 append될 offset이다. 즉 마지막 Record의 offset + 1이며,
 * {@code OffsetSpec.latest()}가 돌려주는 값이다.
 * @param committedOffset Consumer group이 이 partition에 대해 commit해 둔 offset이다. Kafka의 계약상
 * 이 값은 "마지막으로 처리한 offset"이 아니라 "다음에 읽을 offset"이므로, lag을 구할 때 1을 더하거나
 * 빼지 않는다. commit 기록이 없으면 null이다.
 */
public record PartitionOffsets(int partition, long logEndOffset, Long committedOffset) {

}
