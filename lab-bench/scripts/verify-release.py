"""Resolve staged GitHub Release assets from a clean, unauthenticated Gradle consumer."""

import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import zipfile


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


def verify(assets, version, gradle):
    checksums = dict(line.split("  ", 1)[::-1] for line in (assets / "SHA256SUMS").read_text().splitlines())
    assert set(checksums) == {file.name for file in assets.iterdir() if file.name != "SHA256SUMS"}
    for name, checksum in checksums.items():
        assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == checksum, f"Checksum mismatch: {name}"
    metadata = json.loads((assets / f"lab-bench-{version}.module").read_text())
    for variant in metadata["variants"]:
        for dependency in variant.get("dependencies", []):
            if dependency["group"] not in ("org.jetbrains.kotlin", "org.jetbrains.kotlinx"):
                raise AssertionError(f"Unexpected SDK dependency: {dependency['group']}:{dependency['module']}")
    with zipfile.ZipFile(assets / f"lab-bench-{version}.jar") as archive:
        classes = [name for name in archive.namelist() if name.endswith(".class")]
        assert classes, "SDK JAR contains no classes"
        for name in classes:
            assert int.from_bytes(archive.read(name)[6:8], "big") <= 61, f"Requires newer than Java 17: {name}"

    with tempfile.TemporaryDirectory(prefix="lab-bench-consumer-") as temporary:
        root = Path(temporary)
        release = root / "http/releases/download" / f"lab-bench-v{version}"
        release.mkdir(parents=True)
        for artifact in assets.iterdir():
            shutil.copy2(artifact, release / artifact.name)
        handler = functools.partial(QuietHandler, directory=str(root / "http"))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            consumer = root / "consumer"
            consumer.mkdir()
            (consumer / "settings.gradle.kts").write_text('rootProject.name = "sdk-consumer"\n')
            (consumer / "build.gradle.kts").write_text('''
plugins { application }
repositories {
    exclusiveContent {
        forRepository {
            ivy {
                url = uri("http://127.0.0.1:PORT/releases/download")
                isAllowInsecureProtocol = true
                patternLayout { artifact("lab-bench-v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { gradleMetadata() }
            }
        }
        filter { includeModule("com.eignex", "lab-bench") }
    }
    mavenCentral()
}
dependencies { implementation("com.eignex:lab-bench:VERSION") }
application { mainClass.set("Consumer") }
'''.replace("PORT", str(server.server_port)).replace("VERSION", version))
            source = consumer / "src/main/java/Consumer.java"
            source.parent.mkdir(parents=True)
            source.write_text('''
import com.eignex.lab.bench.*;
import java.nio.file.*;
import java.util.*;
public class Consumer {
    private static final BenchSuite SUITE = new BenchSuite() {
        public BenchDescriptor describe() { return new BenchDescriptor("consumer", "Independent build", 1); }
        public List<BenchCase> plan(BenchRequest request) {
            return List.of(new BenchCase("smoke", request.getParameters(), new BenchResources(1, 256, 30, Set.of()), 1));
        }
        public BenchResult run(BenchCase workload, BenchContext context) {
            if (workload.getParameters().containsKey("fail")) throw new IllegalStateException("expected fixture failure");
            try { Files.writeString(context.getOutputDirectory().resolve("capture.txt"), "retained"); }
            catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            return new BenchResult(workload.getId(), BenchStatus.SUCCESS,
                List.of(new BenchMetric("fixture", 42.0, "count", MetricDirection.NONE)),
                List.of(context.artifact("capture.txt", "text/plain", "Fixture capture")), null, null, 1);
        }
    };
    public static void main(String[] args) {
        if (args.length != 0) {
            BenchCli.INSTANCE.execute(SUITE, args, System.getenv(), System.out);
            return;
        }
        BenchDescriptor descriptor = new BenchDescriptor("consumer", "Independent build", 1);
        String json = BenchProtocol.INSTANCE.getJson().encodeToString(BenchDescriptor.Companion.serializer(), descriptor);
        BenchDescriptor decoded = BenchProtocol.INSTANCE.getJson().decodeFromString(BenchDescriptor.Companion.serializer(), json);
        if (!decoded.getId().equals("consumer")) throw new AssertionError(json);
        if (BenchProtocol.VERSION != 1) throw new AssertionError("protocol");
        System.out.println("SDK_CONSUMER_OK");
    }
}
''')
            environment = {key: value for key, value in os.environ.items() if not any(
                word in key.upper() for word in ("TOKEN", "PASSWORD", "SECRET", "CREDENTIAL", "GITHUB", "ORG_GRADLE_PROJECT")
            )}
            environment.pop("GRADLE_OPTS", None)
            environment.pop("JAVA_OPTS", None)
            environment.pop("JAVA_TOOL_OPTIONS", None)
            environment.pop("JDK_JAVA_OPTIONS", None)
            subprocess.run([
                str(gradle), "--no-daemon", "--console=plain", "--stacktrace",
                "--gradle-user-home", str(root / "gradle-home"), "-p", str(consumer), "run", "installDist",
            ], env=environment, check=True, timeout=240)
            launcher = consumer / "build/install/sdk-consumer/bin/sdk-consumer"
            descriptor = subprocess.run([str(launcher), "describe"], env=environment, check=True,
                                        capture_output=True, text=True, timeout=30)
            assert json.loads(descriptor.stdout)["id"] == "consumer"
            request = root / "request.json"
            request.write_text(json.dumps({"protocolVersion": 1, "parameters": {"seed": "139"}}))
            planned = subprocess.run([str(launcher), "plan", str(request)], env=environment, check=True,
                                     capture_output=True, text=True, timeout=30)
            workload = json.loads(planned.stdout)["cases"][0]
            assert workload["parameters"] == {"seed": "139"}
            case = root / "case.json"
            case.write_text(json.dumps(workload))
            output = root / "retained"
            subprocess.run([str(launcher), "run", str(case)], env=dict(environment, OUT=str(output)),
                           check=True, timeout=30)
            result = json.loads((output / "result.json").read_text())
            assert result["status"] == "success" and result["metrics"][0]["value"] == 42
            assert (output / result["artifacts"][0]["path"]).read_text() == "retained"
            workload["parameters"]["fail"] = "true"
            case.write_text(json.dumps(workload))
            failed = subprocess.run([str(launcher), "run", str(case)], env=dict(environment, OUT=str(output)),
                                    capture_output=True, text=True, timeout=30)
            assert failed.returncode != 0
            result = json.loads((output / "result.json").read_text())
            assert result["status"] == "failed" and result["message"] == "expected fixture failure"
            print("SDK_PROCESS_CONTRACT_OK", flush=True)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    verify(Path(sys.argv[1]).resolve(), sys.argv[2], Path(sys.argv[3]).resolve())
