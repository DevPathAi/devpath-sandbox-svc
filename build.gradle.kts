plugins {
	java
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devpath"
version = "0.0.1-SNAPSHOT"
description = "DevPath AI isolated sandbox runner (Docker + gVisor)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// 서비스 특성에 따라 주석 해제해서 사용한다.
	// implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	// runtimeOnly("com.mysql:mysql-connector-j")
	// implementation("org.springframework.boot:spring-boot-starter-data-redis")
	// implementation("org.springframework.boot:spring-boot-starter-security")
	// implementation("org.springframework.kafka:spring-kafka")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

