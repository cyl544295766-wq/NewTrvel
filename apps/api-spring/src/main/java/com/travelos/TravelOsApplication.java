package com.travelos;

import com.travelos.common.EnvLoader;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.travelos.mapper")
public class TravelOsApplication {

  public static void main(String[] args) {
    EnvLoader.loadDotEnv();
    SpringApplication.run(TravelOsApplication.class, args);
  }
}
