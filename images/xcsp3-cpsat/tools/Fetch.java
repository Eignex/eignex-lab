import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a build input and refuses it unless its SHA-256 matches: `java Fetch.java <url> <file> <sha256>`. The JDK
 * base image has no curl, and Docker's legacy builder (which a host without BuildKit uses) has no `ADD --checksum`.
 */
public class Fetch {
    public static void main(String[] args) throws Exception {
        URI url = URI.create(args[0]);
        Path file = Path.of(args[1]);
        String expected = args[2];
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
                if (!actual.equals(expected)) throw new SecurityException("SHA-256 mismatch for " + url + ": " + actual);
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.write(file, response.body());
                return;
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }
}
