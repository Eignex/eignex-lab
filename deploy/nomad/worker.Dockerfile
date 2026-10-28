# Build the distribution first with ./gradlew installDist. Publish to any OCI registry and use its resulting digest.
FROM eclipse-temurin@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e
RUN apt-get update && apt-get install -y --no-install-recommends python3 git time perl util-linux libopenblas0-pthread libgomp1 ca-certificates && rm -rf /var/lib/apt/lists/*
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
