package com.pulseops.report;

/**
 * Report 생성 요청이 실제로 무엇을 했는지이다.
 * <p>
 * 만들었는지 이미 있던 것을 그대로 돌려주었는지를 구분한다. 두 경우 모두 성공이지만 뜻이 다르다.
 * 하루에 한 번 도는 Scheduler가 "이미 있음"을 계속 만나는 것은 같은 날짜를 반복해서 처리하고
 * 있다는 신호이고, 그것은 log에 남아야 알아챌 수 있다.
 * <p>
 * 밖으로 나가지 않는다. Report를 만드는 것은 Scheduler뿐이고 REST에는 생성 경로가 없으므로, 이
 * record와 그것을 만드는 method는 이 package 밖에서 보이지 않는다. Entity를 그대로 담고 있는 것도
 * 그래서 문제가 되지 않는다.
 */
record DailyIncidentReportGenerationResult(DailyIncidentReport report, boolean created) {

}
