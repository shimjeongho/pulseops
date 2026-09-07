# Demo Shop Backend를 Container 하나로 만든다.
#
# builder와 runtime을 나누는 이유는 실행에 필요한 것과 만드는 데 필요한 것이 다르기 때문이다. JDK,
# Gradle 배포본, dependency cache, source는 jar를 만들 때만 필요하고 실행할 때는 쓰이지 않는다. 한
# stage로 만들면 그것들이 전부 image에 남아, 실행에 필요 없는 코드와 도구가 그대로 배포 단위에 들어간다.

FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /workspace

# Wrapper와 build script를 source보다 먼저 넣는다. source만 바뀐 다음 build에서는 아래 dependency
# 내려받기 layer가 그대로 재사용되므로, 코드 한 줄을 고칠 때마다 dependency를 다시 받지 않는다.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./

# gradlew를 직접 실행하지 않고 sh에 넘긴다. 이 repository의 gradlew는 실행 권한 bit 없이(100644)
# 기록되어 있어 ./gradlew가 Permission denied로 끝난다. gradlew는 POSIX sh script이므로 이렇게 부르면
# 권한과 무관하게 동작하며, 권한을 고치려고 build 중에 chmod를 하지 않아도 된다.
RUN sh gradlew --no-daemon --console=plain dependencies

COPY src src

# test를 여기서 실행하지 않는다. bootJar는 원래 test에 의존하지 않으며, 같은 test를 CI와 image build
# 양쪽에서 돌리면 Testcontainers가 Docker 안에서 다시 Docker를 요구하게 된다. test를 실행하는 자리는
# CI와 로컬 Gradle이고, 이 build는 그 결과물을 담기만 한다.
RUN sh gradlew --no-daemon --console=plain bootJar

FROM eclipse-temurin:21-jre-noble AS runtime

# root로 실행하지 않는다. Application이 필요로 하는 권한은 자기 port를 여는 것뿐이고, 그 이상을 주면
# Container를 통해 얻은 실행권이 곧 file system 전체에 대한 권한이 된다.
RUN useradd --system --create-home --uid 10001 demoshop

WORKDIR /app

# bootJar가 만든 실행 가능한 jar 하나만 가져온다. Boot Plugin은 bootJar만 실행하면 -plain.jar를
# 만들지 않으므로 이 pattern에 걸리는 것은 하나뿐이다.
COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar app.jar

USER demoshop

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
