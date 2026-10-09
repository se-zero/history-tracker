package com.history.backend;

import com.history.backend.oauth.config.CimdHttpConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		// JVM 전역 설정이라 스프링이 뜨기 전에(어떤 JDK HttpClient도 만들어지기 전에) 적용해야 한다
		CimdHttpConfig.applyIdleConnectionLimit();
		SpringApplication.run(BackendApplication.class, args);
	}

}
