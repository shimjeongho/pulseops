package com.pulseops.demoshop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/**
	 * 주문과 항목을 한 번의 쿼리로 함께 조회하여 목록을 그릴 때 주문마다 추가 쿼리가 발생하지
	 * 않게 한다. {@code distinct}는 항목 수만큼 같은 주문이 중복되는 것을 막는다.
	 */
	@Query("select distinct o from Order o left join fetch o.items order by o.id")
	List<Order> findAllWithItems();

	@Query("select o from Order o left join fetch o.items where o.id = :orderId")
	Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

}
