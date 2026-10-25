# Build the distribution first with ./gradlew installDist. Publish to any OCI registry and use its resulting digest.
FROM eclipse-temurin@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e
RUN apt-get update && apt-get install -y --no-install-recommends python3 time perl util-linux libopenblas0-pthread libgomp1 ca-certificates && rm -rf /var/lib/apt/lists/*
ARG WORKER_RELEASE
RUN test -n "$WORKER_RELEASE"
ENV LAB_WORKER_RELEASE=$WORKER_RELEASE LAB_SUPERVISOR_SOCKET=/local/process.sock
LABEL org.opencontainers.image.revision=$WORKER_RELEASE
COPY build/install/eignex-lab/ /opt/lab/
COPY src/main/resources/nomad/process_supervisor.py /opt/lab/process_supervisor.py
COPY deploy/nomad/worker-entrypoint.sh /opt/lab/worker-entrypoint.sh
ENTRYPOINT ["/opt/lab/worker-entrypoint.sh"]
