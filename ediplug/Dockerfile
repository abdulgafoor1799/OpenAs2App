# EDIPLUG OpenAS2 image.
# Same build as the upstream Dockerfile, plus the EDIPLUG configuration baked into config_template so every
# container (on any VM) regenerates an identical /opt/openas2/config. Runtime data (partners, partnerships,
# certificates) comes from PostgreSQL; secrets come from environment variables and mounted secret files.
#
# Build from the repository root:
#   docker build -f ediplug/Dockerfile -t openas2:4.15.0-ediplug .
#   docker build -f ediplug/Dockerfile --build-arg SKIP_TESTS=true -t openas2:4.15.0-ediplug .   # faster, no tests

FROM eclipse-temurin:21-jdk-jammy AS builder
ARG SKIP_TESTS=false
COPY . /usr/src/openas2
WORKDIR /usr/src/openas2
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN rm -f Server/dist/* Remote/dist/* Bundle/dist/*
RUN ./mvnw -B clean package -DskipTests=${SKIP_TESTS}
RUN mkdir ./Runtime && unzip -q Server/dist/OpenAS2Server-*.zip -d Runtime
COPY start-container.sh /usr/src/openas2/Runtime/bin/
RUN cd Runtime/bin && chmod 755 *.sh \
    && cd .. && mv config config_template \
    # Demo keystore, demo partnerships and the embedded H2 DB are not used: partnerships come from PostgreSQL
    # and the AS2 keystore is mounted as a secret.
    && rm -rf config_template/as2_certs.p12 config_template/partnerships.xml config_template/DB
COPY ediplug/config/ /usr/src/openas2/Runtime/config_template/


FROM eclipse-temurin:21-jre-jammy
ENV OPENAS2_BASE=/opt/openas2
ENV OPENAS2_HOME=/opt/openas2
ENV OPENAS2_TMPDIR=/opt/openas2/temp
ENV OPENAS2_PROPERTIES_FILE=/opt/openas2/config/openas2.properties
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/src/openas2/Runtime/bin ${OPENAS2_BASE}/bin
COPY --from=builder /usr/src/openas2/Runtime/lib ${OPENAS2_BASE}/lib
COPY --from=builder /usr/src/openas2/Runtime/resources ${OPENAS2_BASE}/resources
COPY --from=builder /usr/src/openas2/Runtime/config_template ${OPENAS2_HOME}/config_template
# Unprivileged user (UID 1000, group 0) as in the upstream image.
RUN useradd --uid 1000 --gid 0 --home-dir ${OPENAS2_HOME} --no-create-home --shell /usr/sbin/nologin openas2 \
    && mkdir -p ${OPENAS2_BASE}/config ${OPENAS2_BASE}/data ${OPENAS2_BASE}/logs ${OPENAS2_BASE}/secrets ${OPENAS2_TMPDIR} \
    && chown -R 1000:0 ${OPENAS2_BASE} \
    && chmod -R g=u ${OPENAS2_BASE}
USER 1000
WORKDIR $OPENAS2_HOME
EXPOSE 10080 10081 10099
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 CMD curl -fsS http://localhost:10099/ || exit 1
ENTRYPOINT ["/opt/openas2/bin/start-container.sh"]
