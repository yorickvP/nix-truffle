package nixtruffle.fetch;

import nixtruffle.fs.Fs;
import nixtruffle.runtime.Bytes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File transfers: {@code file://} URLs from the file system, HTTP(S) with ETags and redirects. */
public final class Download {
    private Download() {}

    private static HttpClient client;

    /** What a download produced; {@code notModified} if the server confirmed {@code expectedEtag}. */
    public record Result(String etag, List<String> urls, String immutableUrl, boolean notModified) {
        public String effectiveUrl() {
            return urls.get(urls.size() - 1);
        }
    }

    private static synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30)).build();
        }
        return client;
    }

    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*;\\s*rel=\"immutable\"");

    /**
     * Downloads {@code url} (a byte string) into {@code sink}. {@code headers} are extra request
     * headers (e.g. access tokens).
     */
    public static Result download(String url, Map<String, String> headers, String expectedEtag, OutputStream sink) {
        Url parsed = Url.parse(url, false);
        if (parsed.scheme.equals("file")) {
            String path = Url.urlPathToPath(parsed.path);
            try {
                Fs.readFile(path, sink);
            } catch (IOException e) {
                throw new FetchException("unable to download '" + url + "': " + e.getMessage());
            }
            return new Result("", List.of(url), null, false);
        }
        if (!parsed.scheme.equals("http") && !parsed.scheme.equals("https")) {
            throw new FetchException("unable to download '" + url + "': unsupported protocol '" + parsed.scheme + "'");
        }
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(Bytes.toJava(parsed.toString())))
                    .header("User-Agent", "nix-truffle/0.1")
                    .timeout(Duration.ofMinutes(30));
            for (var h : headers.entrySet()) req.header(Bytes.toJava(h.getKey()), Bytes.toJava(h.getValue()));
            if (expectedEtag != null && !expectedEtag.isEmpty()) req.header("If-None-Match", Bytes.toJava(expectedEtag));
            HttpResponse<InputStream> res = client().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            List<String> urls = new ArrayList<>();
            urls.add(url);
            String effective = Bytes.fromJava(res.uri().toString());
            if (!effective.equals(url)) urls.add(effective);
            String etag = Bytes.fromJava(res.headers().firstValue("ETag").orElse(""));
            String immutable = null;
            for (String link : res.headers().allValues("Link")) {
                Matcher m = LINK.matcher(link);
                if (m.find()) immutable = Bytes.fromJava(URI.create(res.uri().toString()).resolve(m.group(1)).toString());
            }
            try (InputStream in = res.body()) {
                if (res.statusCode() == 304) return new Result(etag.isEmpty() ? expectedEtag : etag, urls, immutable, true);
                if (res.statusCode() / 100 != 2) {
                    throw new FetchException("unable to download '" + url + "': HTTP error " + res.statusCode());
                }
                in.transferTo(sink);
            }
            return new Result(etag, urls, immutable, false);
        } catch (IOException e) {
            throw new FetchException("unable to download '" + url + "': " + Bytes.fromJava(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted while downloading '" + url + "'");
        } catch (IllegalArgumentException e) {
            throw new FetchException("unable to download '" + url + "': invalid URL");
        }
    }

    /** Downloads into memory. */
    public static byte[] downloadBytes(String url, Map<String, String> headers) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        download(url, headers, null, out);
        return out.toByteArray();
    }
}
