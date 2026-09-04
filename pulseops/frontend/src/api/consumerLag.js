import { getJson } from './client.js'

/**
 * PulseOps가 Telemetry Topic을 얼마나 뒤처져 읽고 있는지를 읽는다.
 *
 * Topic도 Consumer group도 보내지 않는다. 무엇을 볼지는 Backend가 정하며, 화면이 그것을 고를 수 있게
 * 하면 이 경로가 Kafka 안을 들여다보는 통로가 된다.
 */
export function fetchConsumerLag(signal) {
  return getJson('/api/kafka/consumer-lag', signal)
}
