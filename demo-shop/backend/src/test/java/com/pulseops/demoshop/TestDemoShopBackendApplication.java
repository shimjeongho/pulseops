package com.pulseops.demoshop;

import org.springframework.boot.SpringApplication;

public class TestDemoShopBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(DemoShopBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
