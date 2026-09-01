plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.channel"
version = "0.0.1-SNAPSHOT"
description = "integration"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 통합 검색 API 는 MVC(Servlet) 스택으로 서빙한다.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // WebClient 사용 목적으로만 추가한다. 리액티브 웹 스택은 사용하지 않으며,
    // 웹 스택은 application.yaml 의 web-application-type=servlet 으로 고정한다.
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
