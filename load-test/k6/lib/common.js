/**
 * 세 시나리오가 함께 쓰는 대상 주소와 안전 장치이다.
 *
 * k6 script는 Gradle이나 npm dependency가 아니다. Backend와 Frontend는 이 폴더를 알지 못하며, 이
 * 폴더의 코드가 없어도 두 Application은 그대로 빌드되고 실행된다.
 */

/** Demo Shop Backend의 기본 Port이다. 관측 대상은 언제나 Demo Shop이며 PulseOps가 아니다. */
const DEFAULT_BASE_URL = 'http://localhost:8080'

/** 부하를 걸 endpoint이다. 세 시나리오 모두 같은 곳을 때려야 Metric 계열이 하나로 모인다. */
const PRODUCTS_PATH = '/api/products'

const LOCAL_HOSTS = ['localhost', '127.0.0.1', '[::1]']

/** k6에는 URL이 없으므로 host만 직접 떼어 낸다. 여기서 필요한 것은 host 하나뿐이다. */
const HOST_PATTERN = /^https?:\/\/(\[[^\]]+\]|[^/:]+)/

const BASE_URL = (__ENV.DEMO_SHOP_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '')

export const PRODUCTS_URL = `${BASE_URL}${PRODUCTS_PATH}`

/**
 * 실수로 운영 서버에 부하를 보내지 않게 막는다.
 *
 * 이 script들은 초당 수십 건의 요청을 몇 분 동안 보내고, 그중 둘은 일부러 장애를 주입한다. 대상 주소를
 * 환경 변수로 받으므로 오타 하나나 남아 있던 shell 변수 하나로 회사 서버를 향할 수 있고, 그때 잘못을
 * 알아채는 시점은 이미 요청이 나간 뒤이다. 그래서 기본은 로컬만 허용하고, 그 밖의 주소는 사람이
 * ALLOW_LOAD_TEST=true로 한 번 더 말했을 때만 허용한다.
 *
 * setup에서 부른다. VU가 하나라도 요청을 보내기 전에 실행되고, 여기서 던지면 k6가 시험 자체를 중단한다.
 * default 함수 안에서 확인하면 이미 나간 요청은 되돌릴 수 없다.
 */
export function guardTarget() {
  const match = HOST_PATTERN.exec(BASE_URL)
  const host = match ? match[1] : null
  const localTarget = LOCAL_HOSTS.indexOf(host) !== -1

  console.log(`target=${BASE_URL}`)
  console.log(`localTarget=${localTarget}`)

  if (!localTarget && __ENV.ALLOW_LOAD_TEST !== 'true') {
    throw new Error(
      `${BASE_URL}은(는) 로컬 주소가 아니다. 의도한 대상이면 ALLOW_LOAD_TEST=true를 함께 지정한다.`,
    )
  }
}
