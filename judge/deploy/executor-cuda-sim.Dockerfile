# syntax=docker/dockerfile:1
# Judge executor of `cuda-sim`: CUDA on GPGPU-Sim (accel-sim fork) with the QV100 model, no GPU. nvcc builds the
# submission with PTX in the binary and GPGPU-Sim's libcudart; PyTorch for the CPU generates tests; nsjail isolates
# compilation and runs, as in executor.Dockerfile. Builds for linux/amd64 and linux/arm64 (the CUDA image has both): an
# amd64 image run under emulation on an arm64 host cannot start, nsjail's x86 seccomp filter is refused there.
# Build context: the repository root (see compose.yaml).
ARG CUDA_IMAGE=nvidia/cuda:12.8.1-devel-ubuntu24.04@sha256:520292dbb4f755fd360766059e62956e9379485d9e073bbd2f6e3c20c270ed66
ARG CUDA_RELEASE=12.8
ARG GPGPUSIM_COMMIT=4195dd4f229cbf03f4f38c49139ae94d01d23f03
ARG TORCH_VERSION=2.9.1
ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
ARG NUMPY_VERSION=2.2.6
ARG NSJAIL_VERSION=3.6
ARG NSJAIL_COMMIT=f78475530b46d0186111a9096b30725f816b55fe

# ---- nsjail -------------------------------------------------------------------------------------------------
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852 AS nsjail
ARG NSJAIL_VERSION
ARG NSJAIL_COMMIT
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf bison ca-certificates flex g++ gcc git libnl-route-3-dev libprotobuf-dev libtool make pkg-config \
        protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "${NSJAIL_VERSION}" https://github.com/google/nsjail.git /nsjail \
    && test "$(git -C /nsjail rev-parse HEAD)" = "${NSJAIL_COMMIT}" \
    && make -C /nsjail -j"$(nproc)"

# ---- Kotlin service ----------------------------------------------------------------------------------------
FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY judge/judge-api judge/judge-api
COPY judge/judge-gateway judge/judge-gateway
COPY judge/judge-executor judge/judge-executor
RUN ./gradlew --no-daemon -q :judge-executor:bootJar -x test

# ---- runtime: the CUDA toolkit, GPGPU-Sim built in place --------------------------------------------------
FROM ${CUDA_IMAGE}
ARG CUDA_RELEASE
ARG GPGPUSIM_COMMIT
ARG TORCH_VERSION
ARG TORCH_INDEX
ARG NUMPY_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        binutils bison build-essential ca-certificates flex git libgl-dev libnl-route-3-200 libprotobuf32t64 patchelf \
        python3 python3-venv \
        xutils-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
ENV CUDA_INSTALL_PATH=/usr/local/cuda
# runtime/ points at the libcudart GPGPU-Sim builds; VERSION is the commit the executor checks. The make files of its
# power model (accelwattch) pass x86-only flags to g++, which the arm64 compiler rejects: they are dropped there.
# The simulator learns the CUDA version of a program from `ldd`'s `libcudart.so.<version>` and refuses to run without
# it, but its libcudart is named plain `libcudart.so`: the soname gets the version, so programs linked with it need
# `libcudart.so.12` (the build already puts that link next to it).
ARG TARGETARCH
RUN git clone -q https://github.com/accel-sim/gpgpu-sim_distribution.git /opt/gpgpu-sim \
    && cd /opt/gpgpu-sim && git checkout -q "${GPGPUSIM_COMMIT}" \
    && if [ "${TARGETARCH}" = arm64 ]; then \
        sed -i -e 's/ -m64//g' -e 's/ -msse2//g' -e 's/ -mfpmath=sse//g' \
            src/accelwattch/mcpat.mk src/accelwattch/mcpatXeonCore.mk src/accelwattch/cacti/cacti.mk; \
    fi \
    && bash -c 'source setup_environment release > /dev/null && make -j"$(nproc)"' \
    && ln -s "$(dirname "$(ls /opt/gpgpu-sim/lib/*/cuda-*/release/libcudart.so)")" /opt/gpgpu-sim/runtime \
    && test -L /opt/gpgpu-sim/runtime/libcudart.so.12 \
    && patchelf --set-soname libcudart.so.12 /opt/gpgpu-sim/runtime/libcudart.so \
    && echo "${GPGPUSIM_COMMIT}" > /opt/gpgpu-sim/VERSION \
    && rm -rf /opt/gpgpu-sim/build /opt/gpgpu-sim/.git
COPY judge/deploy/executor-emu-requirements.txt /tmp/requirements.txt
RUN grep -qx "torch==${TORCH_VERSION}+cpu" /tmp/requirements.txt && grep -qx "numpy==${NUMPY_VERSION}" /tmp/requirements.txt \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --index-url "${TORCH_INDEX}" --extra-index-url https://pypi.org/simple -r /tmp/requirements.txt
COPY --from=eclipse-temurin:26-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3 /opt/java/openjdk /opt/java/openjdk
COPY --from=nsjail /nsjail/nsjail /usr/local/bin/nsjail
COPY judge/pytools /opt/judge/pytools
COPY judge/sandbox /opt/judge/sandbox
# modify_ldt and uselib exist on x86 only, and kafel refuses a policy that names a syscall the architecture lacks
ARG TARGETARCH
RUN if [ "${TARGETARCH}" = arm64 ]; then \
        grep -q "modify_ldt, uselib, " /opt/judge/sandbox/seccomp.kafel \
        && sed -i "s/modify_ldt, uselib, //" /opt/judge/sandbox/seccomp.kafel; \
    fi
COPY --from=build /src/judge/judge-executor/build/libs/judge-executor.jar /opt/judge/judge-executor.jar

ENV JUDGE_PROCESSORS=cuda-sim \
    JUDGE_CUDA_RELEASE=${CUDA_RELEASE} \
    JUDGE_GPGPUSIM_VERSION=${GPGPUSIM_COMMIT} \
    JUDGE_TORCH_VERSION=${TORCH_VERSION} \
    JUDGE_PYTHON=/opt/venv/bin/python \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/cuda/bin:${PATH}
VOLUME ["/var/lib/judge"]
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=25", "-jar", "/opt/judge/judge-executor.jar"]
