package com.javaee.docai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@MapperScan(basePackages = {
    "com.javaee.docai.user.mapper",
    "com.javaee.docai.file.mapper",
    "com.javaee.docai.doc.mapper"
})
public class DocAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocAiApplication.class, args);
    }
}
