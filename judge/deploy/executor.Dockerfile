# syntax=docker/dockerfile:1
# Judge executor: pinned CUDA toolkit + host compiler + PyTorch + nsjail + the Kotlin service.
# Build context: the repository root (see compose.yaml).
#
# Pinned toolchain (single source of truth; the executor verifies it at startup and refuses to run on mismatch):
ARG CUDA_IMAGE=nvidia/cuda:12.8.1-devel-ubuntu24.04
ARG CUDA_RELEASE=12.8
ARG GCC_MAJOR=13
ARG TORCH_VERSION=2.9.1
ARG TORCH_INDEX=https://download.pytorch.org/whl/cu128
ARG NUMPY_VERSION=2.2.6
ARG NSJAIL_VERSION=3.6

# ---- nsjail -------------------------------------------------------------------------------------------------
FROM ubuntu:24.04 AS nsjail
ARG NSJAIL_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf bison ca-certificates flex g++ gcc git libnl-route-3-dev libprotobuf-dev libtool make pkg-config \
        protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "${NSJAIL_VERSION}" https://github.com/google/nsjail.git /nsjail \
    && make -C /nsjail -j"$(nproc)"

# ---- Kotlin service ----------------------------------------------------------------------------------------
FROM eclipse-temurin:26-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (project dirs must exist for settings.gradle.kts)
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY judge/judge-api judge/judge-api
COPY judge/judge-gateway judge/judge-gateway
COPY judge/judge-executor judge/judge-executor
RUN ./gradlew --no-daemon -q :judge-executor:bootJar -x test

# ---- runtime ----------------------------------------------------------------------------------------------
FROM ${CUDA_IMAGE}
ARG CUDA_RELEASE
ARG GCC_MAJOR
ARG TORCH_VERSION
ARG TORCH_INDEX
ARG NUMPY_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        binutils g++-${GCC_MAJOR} libnl-route-3-200 libprotobuf32t64 python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir "numpy==${NUMPY_VERSION}" \
    && /opt/venv/bin/pip install --no-cache-dir "torch==${TORCH_VERSION}" --index-url "${TORCH_INDEX}"
COPY --from=eclipse-temurin:26-jre-noble /opt/java/openjdk /opt/java/openjdk
COPY --from=nsjail /nsjail/nsjail /usr/local/bin/nsjail
COPY judge/harness /opt/judge/harness
COPY judge/pytools /opt/judge/pytools
COPY judge/sandbox /opt/judge/sandbox
COPY --from=build /src/judge/judge-executor/build/libs/judge-executor.jar /opt/judge/judge-executor.jar

ENV JUDGE_CUDA_RELEASE=${CUDA_RELEASE} \
    JUDGE_GCC_MAJOR=${GCC_MAJOR} \
    JUDGE_HOST_CXX=g++-${GCC_MAJOR} \
    JUDGE_TORCH_VERSION=${TORCH_VERSION} \
    JUDGE_PYTHON=/opt/venv/bin/python \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/cuda/bin:${PATH}
VOLUME ["/var/lib/judge"]
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=25", "-jar", "/opt/judge/judge-executor.jar"]
