package com.pulseops.demoshop.faultinjection;

/**
 * 의도적으로 주입한 장애를 나타낸다. Demo Shop의 실제 Business 실패와 구분되어야 하므로 기존
 * Business Exception을 재사용하지 않고 전용 Exception을 둔다.
 */
public class DemoFaultInjectedException extends RuntimeException {

}
