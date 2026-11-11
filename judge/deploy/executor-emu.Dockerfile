# syntax=docker/dockerfile:1
# Judge executor of the emulated processors, no GPU: PyTorch for the CPU generates tests; `rtl` synthesizes and
# simulates circuits with the OSS CAD Suite (Yosys, Verilator); `riscv-rvv` cross-compiles with GCC and runs under
# QEMU user mode with the judge's plugin; `coral-npu` cross-compiles bare-metal programs with GCC and picolibc and
# runs them on the Coral NPU core of Renode. nsjail isolates them all, as in executor.Dockerfile.
# Build context: the repository root (see compose.yaml). Builds for linux/amd64 and linux/arm64.
#
# Pinned toolchain: the executor verifies the Yosys, Verilator, QEMU, Renode and GCC versions at startup and refuses to
# run on another. The OSS CAD Suite is a nightly build, pinned by its date and the sha256 of each archive; Renode is a
# release, pinned by the sha256 of each archive too.
ARG UBUNTU_IMAGE=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
ARG GCC_MAJOR=13
ARG TORCH_VERSION=2.9.1
ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
ARG NUMPY_VERSION=2.2.6
ARG NSJAIL_VERSION=3.6
ARG NSJAIL_COMMIT=f78475530b46d0186111a9096b30725f816b55fe
ARG OSS_CAD_SUITE_DATE=2026-11-01
ARG OSS_CAD_SUITE_STAMP=20261101
ARG OSS_CAD_SUITE_SHA256_AMD64=c61fb5b91c72ecb1f2a61b166d65fd387d5ad51b12476078e96ef8db1c6f37a3
ARG OSS_CAD_SUITE_SHA256_ARM64=8831d0560e557fd2c753a5d5d98b772218a3466575dffcc00977ac5667d669af
ARG YOSYS_VERSION=0.70+286
ARG VERILATOR_VERSION=5.053
ARG QEMU_VERSION=11.1.3
ARG QEMU_SHA256=5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22
ARG RISCV_GCC_VERSION=13.3
ARG RENODE_VERSION=1.17.0
ARG RENODE_SHA256_AMD64=4ba7c68b59e2447f188ef4b4b112fcccd2802582c460beedceb63b84e5605a5f
ARG RENODE_SHA256_ARM64=e9051578411441bfb47b77de0381cfba3badce03663367fab561472fd7807f1f
ARG CORAL_NPU_GCC_VERSION=13.2

# ---- nsjail -------------------------------------------------------------------------------------------------
FROM ${UBUNTU_IMAGE} AS nsjail
ARG NSJAIL_VERSION
ARG NSJAIL_COMMIT
RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf bison ca-certificates flex g++ gcc git libnl-route-3-dev libprotobuf-dev libtool make pkg-config \
        protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "${NSJAIL_VERSION}" https://github.com/google/nsjail.git /nsjail \
    && test "$(git -C /nsjail rev-parse HEAD)" = "${NSJAIL_COMMIT}" \
    && make -C /nsjail -j"$(nproc)"

# ---- OSS CAD Suite ------------------------------------------------------------------------------------------
FROM ${UBUNTU_IMAGE} AS oss-cad-suite
ARG TARGETARCH
ARG OSS_CAD_SUITE_DATE
ARG OSS_CAD_SUITE_STAMP
ARG OSS_CAD_SUITE_SHA256_AMD64
ARG OSS_CAD_SUITE_SHA256_ARM64
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH}" in \
        amd64) arch=x64; sha="${OSS_CAD_SUITE_SHA256_AMD64}" ;; \
        arm64) arch=arm64; sha="${OSS_CAD_SUITE_SHA256_ARM64}" ;; \
        *) echo "no OSS CAD Suite for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/oss.tgz \
        "https://github.com/YosysHQ/oss-cad-suite-build/releases/download/${OSS_CAD_SUITE_DATE}/oss-cad-suite-linux-${arch}-${OSS_CAD_SUITE_STAMP}.tgz" \
    && echo "${sha}  /tmp/oss.tgz" | sha256sum -c - \
    && tar -xzf /tmp/oss.tgz -C /opt \
    && rm /tmp/oss.tgz

# ---- QEMU user mode with plugins, and the judge's plugin (riscv-rvv) ---------------------------------------
FROM ${UBUNTU_IMAGE} AS qemu
ARG QEMU_VERSION
ARG QEMU_SHA256
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl libglib2.0-dev meson ninja-build pkg-config python3 python3-venv xz-utils \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/qemu.tar.xz "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz" \
    && echo "${QEMU_SHA256}  /tmp/qemu.tar.xz" | sha256sum -c - \
    && tar -xJf /tmp/qemu.tar.xz -C /tmp \
    && cd "/tmp/qemu-${QEMU_VERSION}" && mkdir build && cd build \
    && ../configure --target-list=riscv64-linux-user --enable-plugins --disable-system --disable-docs --disable-werror \
        --disable-tools --prefix=/opt/qemu \
    && make -j"$(nproc)" && make install
COPY judge/riscv/judge_plugin.c /tmp/judge_plugin.c
RUN mkdir -p /opt/judge/riscv \
    && gcc -O2 -shared -fPIC -I/opt/qemu/include $(pkg-config --cflags glib-2.0) -o /opt/judge/riscv/judge_plugin.so \
        /tmp/judge_plugin.c $(pkg-config --libs glib-2.0)

# ---- Renode, with its own .NET runtime (coral-npu) ----------------------------------------------------------
FROM ${UBUNTU_IMAGE} AS renode
ARG TARGETARCH
ARG RENODE_VERSION
ARG RENODE_SHA256_AMD64
ARG RENODE_SHA256_ARM64
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH}" in \
        amd64) name="renode-${RENODE_VERSION}.linux-portable.tar.gz"; sha="${RENODE_SHA256_AMD64}" ;; \
        arm64) name="renode-${RENODE_VERSION}.linux-arm64-portable.tar.gz"; sha="${RENODE_SHA256_ARM64}" ;; \
        *) echo "no Renode for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/renode.tgz "https://github.com/renode/renode/releases/download/v${RENODE_VERSION}/${name}" \
    && echo "${sha}  /tmp/renode.tgz" | sha256sum -c - \
    && mkdir /opt/renode && tar -xzf /tmp/renode.tgz -C /opt/renode --strip-components=1 \
    && rm /tmp/renode.tgz

# ---- Kotlin service ----------------------------------------------------------------------------------------
FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
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
FROM ${UBUNTU_IMAGE}
ARG GCC_MAJOR
ARG TORCH_VERSION
ARG TORCH_INDEX
ARG NUMPY_VERSION
ARG YOSYS_VERSION
ARG VERILATOR_VERSION
ARG QEMU_VERSION
ARG RISCV_GCC_VERSION
ARG RENODE_VERSION
ARG CORAL_NPU_GCC_VERSION
# Verilator turns the netlist into C++ and builds the simulator with make and the system's g++; it is a perl script.
# riscv-rvv: the cross compilers, and glib for QEMU and the plugin. coral-npu: the bare-metal cross compilers (the
# package has gcc and g++) and picolibc, the C library they link.
RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ g++-riscv64-linux-gnu gcc-riscv64-linux-gnu gcc-riscv64-unknown-elf libglib2.0-0t64 libnl-route-3-200 \
        libprotobuf32t64 make perl picolibc-riscv64-unknown-elf python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && test "$(g++ -dumpversion | cut -d. -f1)" = "${GCC_MAJOR}" \
    && riscv64-linux-gnu-gcc -dumpfullversion | grep -q "^${RISCV_GCC_VERSION}" \
    && riscv64-unknown-elf-gcc -dumpfullversion | grep -q "^${CORAL_NPU_GCC_VERSION}"
# every package pinned, transitive ones included; the two ARGs above must agree with the file
COPY judge/deploy/executor-emu-requirements.txt /tmp/requirements.txt
RUN grep -qx "torch==${TORCH_VERSION}+cpu" /tmp/requirements.txt && grep -qx "numpy==${NUMPY_VERSION}" /tmp/requirements.txt \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --index-url "${TORCH_INDEX}" --extra-index-url https://pypi.org/simple -r /tmp/requirements.txt
COPY --from=oss-cad-suite /opt/oss-cad-suite /opt/oss-cad-suite
COPY --from=qemu /opt/qemu /opt/qemu
COPY --from=qemu /opt/judge/riscv /opt/judge/riscv
COPY --from=renode /opt/renode /opt/renode
COPY judge/coralnpu /opt/judge/coralnpu
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

ENV JUDGE_PROCESSORS=rtl,riscv-rvv,coral-npu \
    JUDGE_RTL_HOME=/opt/oss-cad-suite \
    JUDGE_QEMU_VERSION=${QEMU_VERSION} \
    JUDGE_RISCV_GCC_VERSION=${RISCV_GCC_VERSION} \
    JUDGE_RENODE_VERSION=${RENODE_VERSION} \
    JUDGE_CORAL_NPU_GCC_VERSION=${CORAL_NPU_GCC_VERSION} \
    JUDGE_YOSYS_VERSION=${YOSYS_VERSION} \
    JUDGE_VERILATOR_VERSION=${VERILATOR_VERSION} \
    JUDGE_TORCH_VERSION=${TORCH_VERSION} \
    JUDGE_PYTHON=/opt/venv/bin/python \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:${PATH}
VOLUME ["/var/lib/judge"]
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=25", "-jar", "/opt/judge/judge-executor.jar"]
