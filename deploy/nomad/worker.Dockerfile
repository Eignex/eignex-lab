# Build the distribution first with ./gradlew installDist. Publish to any OCI registry and use its resulting digest.
FROM eclipse-temurin@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e
RUN apt-get update && apt-get install -y --no-install-recommends python3 git time perl util-linux zstd xz-utils bzip2 libopenblas0-pthread libopenblas-dev libgomp1 ca-certificates && rm -rf /var/lib/apt/lists/*
# oneMKL for BLAS comparisons, x86_64 only: Intel's redistributable runtime, registered with ld.so (/opt/intel/oneapi/redist/lib).
RUN if [ "$(dpkg --print-architecture)" = amd64 ]; then \
      apt-get update && apt-get install -y --no-install-recommends curl gpg \
      && curl -fsSL https://apt.repos.intel.com/intel-gpg-keys/GPG-PUB-KEY-INTEL-SW-PRODUCTS.PUB | gpg --dearmor -o /usr/share/keyrings/intel-oneapi.gpg \
      && echo "deb [signed-by=/usr/share/keyrings/intel-oneapi.gpg] https://apt.repos.intel.com/oneapi all main" > /etc/apt/sources.list.d/oneapi.list \
      && apt-get update && apt-get install -y --no-install-recommends intel-oneapi-runtime-mkl=2026.2.0-881 \
      && apt-get purge -y --auto-remove curl gpg && rm -rf /var/lib/apt/lists/* \
      && ldconfig -p | grep -q libmkl_rt.so; \
    fi
RUN ldconfig -p | grep -q 'libopenblas.so '
# MiniZinc 2.10.1 and bundled reference solvers, for both amd64 and arm64. Checkers never invoke these solvers.
COPY --from=ghcr.io/minizinc/minizinc:2.10.1-dist@sha256:2c4fb9a528a194d78558b28edf7471f5a32752b87741abf8bc16cb1e85a04254 /opt/minizinc /opt/minizinc
ENV PATH=/opt/minizinc/bin:$PATH LD_LIBRARY_PATH=/opt/minizinc/lib
RUN minizinc --version && minizinc --solvers
ARG WORKER_RELEASE
RUN test -n "$WORKER_RELEASE"
ENV LAB_WORKER_RELEASE=$WORKER_RELEASE LAB_SUPERVISOR_SOCKET=/local/process.sock LAB_WORKER_LAUNCHER=/opt/lab/bin/eignex-lab
LABEL org.opencontainers.image.revision=$WORKER_RELEASE
COPY build/install/eignex-lab/ /opt/lab/
COPY src/main/resources/nomad/process_supervisor.py /opt/lab/process_supervisor.py
COPY deploy/nomad/worker-entrypoint.sh /opt/lab/worker-entrypoint.sh
ENTRYPOINT ["/opt/lab/worker-entrypoint.sh"]
