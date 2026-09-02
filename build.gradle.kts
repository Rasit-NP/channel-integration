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

    // 저장하는 것은 공급사 코드 ↔ 내부 식별자 매핑뿐이다. 테이블 2개에 조인이 없어
    // ORM 이 줄여줄 코드가 거의 없으므로 JdbcClient 로 간다. 스키마는 schema.sql 이 소유한다.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mock 공급사를 테스트에서 직접 띄워 실제 HTTP 로 검증한다.
    // 운영 의존성이 아니라 테스트 픽스처다.
    testImplementation(project(":mock-supplier"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
