package com.pulseops.demoshop.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProductRepository extends JpaRepository<Product, Long> {

	/**
	 * Loads the given products for update, so concurrent payments for the same product
	 * are serialised instead of both reading the same stock. The rows are taken in
	 * ascending id order so that payments touching overlapping sets of products always
	 * acquire their locks in the same sequence and cannot deadlock against each other.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Product> findAllByIdInOrderByIdAsc(Collection<Long> ids);

}
