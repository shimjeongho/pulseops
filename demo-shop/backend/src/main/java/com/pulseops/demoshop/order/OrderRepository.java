package com.pulseops.demoshop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/**
	 * Loads every order with its items in one query, so rendering a list does not issue
	 * a query per order. {@code distinct} keeps an order from appearing once per item.
	 */
	@Query("select distinct o from Order o left join fetch o.items order by o.id")
	List<Order> findAllWithItems();

	@Query("select o from Order o left join fetch o.items where o.id = :orderId")
	Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

}
