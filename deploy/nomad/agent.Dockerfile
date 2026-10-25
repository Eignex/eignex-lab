ARG BASE
FROM ${BASE}
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 ca-certificates procps \
    && rm -rf /var/lib/apt/lists/*
