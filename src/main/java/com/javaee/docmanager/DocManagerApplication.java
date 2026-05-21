package com.javaee.docmanager;

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
    "com.javaee.docmanager.user.mapper",
    "com.javaee.docmanager.file.mapper",
    "com.javaee.docmanager.doc.mapper",
    "com.javaee.docmanager.ai.mapper"
})
public class DocManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocManagerApplication.class, args);
    }
}
