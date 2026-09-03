package com.pulseops.demoshop.faultinjection;

/**
 * 알 수 없는 Fault 유형이 요청됐음을 나타낸다. Enum 변환 실패를 그대로 두면 처리되지 않은
 * Exception이 되어 500으로 끝나고, 잘못된 요청 하나가 서비스 오류처럼 관측된다.
 */
public class InvalidDemoFaultException extends RuntimeException {

}
