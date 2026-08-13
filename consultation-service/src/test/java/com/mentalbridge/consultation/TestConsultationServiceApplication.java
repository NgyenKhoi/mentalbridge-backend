package com.mentalbridge.consultation;

import org.springframework.boot.SpringApplication;

public class TestConsultationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ConsultationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
