package com.pulseops.demoshop.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProductRepository extends JpaRepository<Product, Long> {

	/**
	 * 주어진 상품을 쓰기 잠금과 함께 조회한다. 같은 상품에 대한 동시 결제가 같은 재고를 함께 읽지
	 * 않고 직렬화되도록 하기 위한 것이다. 상품 ID 오름차순으로 조회하므로, 겹치는 상품을 다루는
	 * 결제들이 항상 같은 순서로 잠금을 획득하여 서로 Deadlock에 빠지지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Product> findAllByIdInOrderByIdAsc(Collection<Long> ids);

}
