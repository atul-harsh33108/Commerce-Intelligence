# One Dockerfile for all nine Java services, selected by build arg.
#
# WHY ONE FILE. Nine near-identical Dockerfiles drift: someone bumps the base image in six of them,
# and the two that were missed turn up in a CVE scan months later. From the container's point of
# view the services genuinely are identical - a Spring Boot jar, a port, a health endpoint - so the
# thing that differs is a variable, not a file.
#
# WHY THE JAR IS BUILT OUTSIDE. This packages a jar that `./mvnw package` already produced rather
# than running Maven in a build stage. A deliberate trade:
#
#   for     - the reactor builds all nine modules once, sharing one dependency cache. Nine in-image
#             builds re-resolve the same tree nine times; on a cold cache that is twenty minutes of
#             downloading the same jars.
#   for     - the test gate lives in CI, where a failure is reported in two minutes rather than
#             after a fifteen-minute image build.
#   against - the image is reproducible from a known-good jar, not from a git SHA alone. That is the
#             real cost, and it is why CI builds jar and image in the same job from one checkout.
#
# With a remote build cache the in-image build wins. Here it does not.

# ---- extract ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS extract

# No default. An unset MODULE should fail loudly rather than quietly produce an image of whatever
# happened to match a glob.
ARG MODULE

WORKDIR /build
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar application.jar

# SPLIT THE JAR, do not copy it whole.
#
# A Spring Boot jar here is ~90 MB, of which ~88 MB is dependencies that change when the pom changes
# - rarely - and ~2 MB is application code that changes every commit. Copied as one file, every
# rebuild pushes 90 MB to the registry and every node re-pulls it. `jarmode=tools extract` splits it
# into `lib/` and a small launcher jar, which become two image layers below.
RUN java -Djarmode=tools -jar application.jar extract --destination extracted

# ---- runtime ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# curl for the healthcheck. busybox wget is already present, but its exit codes on an HTTP error are
# not what a compose healthcheck expects - which yields containers reported healthy while returning
# 503.
RUN apk add --no-cache curl \
    # A non-root user, created rather than assumed. The base image runs as root and a Spring Boot
    # service needs nothing root can do: it binds a port above 1024 and writes nothing outside /tmp.
    && addgroup -S app && adduser -S -G app app

WORKDIR /app
USER app

# ORDER IS THE POINT. Dependencies first, because that layer is identical across commits and is
# reused; the application jar last, because it changes every time. Reversing these two lines makes
# the split worthless.
COPY --from=extract --chown=app:app /build/extracted/lib ./lib
COPY --from=extract --chown=app:app /build/extracted/application.jar ./application.jar

ARG MODULE
LABEL org.opencontainers.image.title=${MODULE}

# MaxRAMPercentage rather than -Xmx: the JVM reads the cgroup limit, so one setting is correct at
# every container size and nothing needs re-tuning when the memory limit changes.
#
# ExitOnOutOfMemoryError because a JVM that has OOMed does not recover - it thrashes GC while still
# accepting TCP connections and passing a naive health check. Dying lets the orchestrator replace it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
