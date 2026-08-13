package com.mentalbridge.care;

import org.springframework.boot.SpringApplication;

public class TestCareServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CareServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
