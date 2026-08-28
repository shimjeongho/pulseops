package com.pulseops.demoshop.product;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

	private final ProductRepository productRepository;

	public ProductService(ProductRepository productRepository) {
		this.productRepository = productRepository;
	}

	public List<Product> findAll() {
		return this.productRepository.findAll();
	}

	public Product getById(Long productId) {
		return this.productRepository.findById(productId)
			.orElseThrow(() -> new ProductNotFoundException(productId));
	}

}
