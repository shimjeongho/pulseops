/**
 * 화면에 남아 있는 값이 방금 받은 값이 아니라는 것을 알린다.
 *
 * 갱신 실패를 오류 상자로 그리지 않는 이유는, 지금 보이는 값 자체는 유효하기 때문이다. 운영자가
 * 알아야 할 것은 "실패했다"가 아니라 "이 숫자가 언제 것인지 더 이상 장담할 수 없다"이다.
 */
export function StaleNotice({ title, error }) {
  return (
    <div className="notice" role="status">
      <p className="notice-title">{title}</p>
      <p className="notice-detail">마지막으로 받은 값을 그대로 보여 줍니다. {error.message}</p>
    </div>
  )
}
