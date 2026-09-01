// 루트 프로젝트가 플러그인을 버전과 함께 적용했으므로 여기서는 버전 없이 적용한다.
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.channel"
version = "0.0.1-SNAPSHOT"
description = "mock-supplier"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// 테스트 대상이 아니라 검증 도구다. 고정 응답을 주는 것이 전부이므로 web 하나면 충분하다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
}
