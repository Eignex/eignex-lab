# XCSP3 cp-sat reference solver: a JVM program reads the XCSP3 `.xml` with the XCSP3-Java-Tools parser and solves it
# with OR-Tools cp-sat through its Java bindings, so there is no XCSP3->FlatZinc conversion and nothing is written to
# disk. The JVM bench runs this per instance and parses the witness and JSON verdict off stdout. Every input is pinned:
# the base images by digest, each downloaded archive by SHA-256.
ARG JDK_IMAGE=eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a
ARG JRE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce

FROM ${JDK_IMAGE} AS build
SHELL ["/bin/bash", "-euo", "pipefail", "-c"]
WORKDIR /build
# OR-Tools 9.15.6755 is the release the former CPMpy image pinned (ortools==9.15.6755); protobuf-java 4.33.1 is the
# version its Java bindings declare. The XCSP3-Java-Tools revision and archive hash are the ones
# xcsp3-tools/build.gradle.kts builds the lab's checker from. Both native builds are fetched; the image keeps its own.
ADD --checksum=sha256:483f27408386fd1ab718aa72244beead10756e1be6d25c7ab7fcb83eb1788d9a \
  https://repo1.maven.org/maven2/com/google/ortools/ortools-java/9.15.6755/ortools-java-9.15.6755.jar lib/ortools-java.jar
ADD --checksum=sha256:fd5cf3d55bc2c3ddb2a8640c9d4c69daa9a5b326fb6e05bae0e56b3f4f85e0f7 \
  https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/4.33.1/protobuf-java-4.33.1.jar lib/protobuf-java.jar
ADD --checksum=sha256:6f5870f5a229a6916407fbf821f1d58a1a95405aa46d79542b59845f7aa30278 \
  https://repo1.maven.org/maven2/com/google/ortools/ortools-linux-x86-64/9.15.6755/ortools-linux-x86-64-9.15.6755.jar native-amd64.jar
ADD --checksum=sha256:4c61daad85c14cecdab8df5097122e46ccd73a64f4acf41e5933db40eb6456cc \
  https://repo1.maven.org/maven2/com/google/ortools/ortools-linux-aarch64/9.15.6755/ortools-linux-aarch64-9.15.6755.jar native-arm64.jar
ADD --checksum=sha256:3fdfc37e29744a9a67dd5365e81467e26fbde0b7aa204e6f8bbe79eeaa7ae892 \
  https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar junit.jar
ADD --checksum=sha256:541624efb4ac227d0c99c7058e2e07041ff4f119070b0c996cde539fe71cc296 \
  https://codeload.github.com/xcsp3team/XCSP3-Java-Tools/tar.gz/ca58fc79a42374d080aac4d1410874e748cf0a05 xcsp3.tar.gz
RUN arch="$(dpkg --print-architecture)" \
 && { [ -f "native-$arch.jar" ] || { echo "no OR-Tools build for $arch" >&2; exit 1; }; } \
 && mkdir native && (cd native && jar xf "../native-$arch.jar" && mv ortools-linux-*/* . && rm -rf ortools-linux-* META-INF) \
 && mkdir xcsp3 && tar xzf xcsp3.tar.gz --strip-components=1 -C xcsp3
# Only the parser and the common model of XCSP3-Java-Tools: the modeler and its JSON dependency are not needed.
RUN mkdir -p classes/xcsp3 \
 && javac -nowarn -encoding UTF-8 --release 25 -d classes/xcsp3 \
      $(find xcsp3/src/main/java/org/xcsp/common xcsp3/src/main/java/org/xcsp/parser -name '*.java') \
 && cp xcsp3/LICENSE classes/xcsp3/LICENSE-XCSP3 \
 && jar --create --file lib/xcsp3-tools.jar -C classes/xcsp3 .
COPY src/main/java src/main/java
RUN mkdir -p classes/main \
 && javac -encoding UTF-8 --release 25 -cp 'lib/*' -d classes/main $(find src/main/java -name '*.java') \
 && jar --create --file lib/xcsp3-cpsat.jar -C classes/main .

# `docker build --target test` runs the JUnit suite against the image's own JVM and native OR-Tools build.
FROM build AS test
COPY src/test src/test
RUN mkdir -p classes/test \
 && javac -encoding UTF-8 --release 25 -cp 'lib/*:junit.jar' -d classes/test $(find src/test/java -name '*.java') \
 && cp -r src/test/resources/. classes/test/ \
 && java --enable-native-access=ALL-UNNAMED -Djava.library.path=/build/native -jar junit.jar execute \
      --class-path 'lib/ortools-java.jar:lib/protobuf-java.jar:lib/xcsp3-tools.jar:lib/xcsp3-cpsat.jar:classes/test' \
      --scan-class-path classes/test --fail-if-no-tests --disable-banner --details=tree

FROM ${JRE_IMAGE}
COPY --from=build /build/lib/ortools-java.jar /build/lib/protobuf-java.jar /build/lib/xcsp3-tools.jar \
  /build/lib/xcsp3-cpsat.jar /opt/xcsp3-cpsat/lib/
COPY --from=build /build/native /opt/xcsp3-cpsat/native
COPY src/test/resources/smoke.xml /opt/xcsp3-cpsat/smoke.xml
ENV XCSP3_CPSAT_CP=/opt/xcsp3-cpsat/lib/xcsp3-cpsat.jar:/opt/xcsp3-cpsat/lib/xcsp3-tools.jar:/opt/xcsp3-cpsat/lib/ortools-java.jar:/opt/xcsp3-cpsat/lib/protobuf-java.jar
# A training solve records the classes start-up loads in an AOT cache, which every later solve maps instead of loading.
# The cache only applies to the same JVM, flags and class path, so the training run repeats the entrypoint's, and it
# sizes the heap as under the lab's 6g container limit, where compressed oops are on as they are at run time.
RUN java -XX:AOTCacheOutput=/opt/xcsp3-cpsat/xcsp3-cpsat.aot -Xlog:all=warning:stderr -XX:+UseSerialGC \
      -XX:MaxRAMPercentage=50 -XX:MaxRAM=6g --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
      -Djava.library.path=/opt/xcsp3-cpsat/native -cp "$XCSP3_CPSAT_CP" com.eignex.lab.xcsp3cpsat.Main /opt/xcsp3-cpsat/smoke.xml 1 > /dev/null
# Args: <instance.xml> [time_limit_seconds] [workers] [seed]. Mount the instance dir read-only; prints the witness and a
# JSON verdict on stdout. JVM diagnostics go to stderr; the heap may take half of the container's memory limit, the
# rest is left to cp-sat's native search.
ENTRYPOINT ["java", "-XX:AOTCache=/opt/xcsp3-cpsat/xcsp3-cpsat.aot", "-Xlog:all=warning:stderr", "-XX:+UseSerialGC", \
  "-XX:MaxRAMPercentage=50", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-Djava.library.path=/opt/xcsp3-cpsat/native", \
  "-cp", "/opt/xcsp3-cpsat/lib/xcsp3-cpsat.jar:/opt/xcsp3-cpsat/lib/xcsp3-tools.jar:/opt/xcsp3-cpsat/lib/ortools-java.jar:/opt/xcsp3-cpsat/lib/protobuf-java.jar", \
  "com.eignex.lab.xcsp3cpsat.Main"]
