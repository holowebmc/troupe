package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Callback;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The server's {@code http} module (the prelude turns it into calls that wait):
 * <pre>
 * local r = http.get(url, { headers = {...}, timeout = 10 })      r.status  r.body  r.headers['content-type']
 * http.post(url, json.encode(data), { headers = { ['content-type'] = 'application/json' } })
 * http.request(url, { method = 'PUT', body = '...', headers = {...}, timeout = 10 })
 * </pre>
 * Any status is a result; only a request that gets no response is an error. Bodies are text (UTF-8), up to 4 MiB.
 */
final class HttpApi {
    private static final int MAX_BODY = 4 << 20;
    private static HttpClient client;

    private HttpApi() {}

    static void register(ModuleRegistry modules) {
        modules.global("http").function("request", a -> {
            URI uri = URI.create(a.string(0));
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IllegalArgumentException("only http and https URLs");
            Map<String, Object> opts = a.table(1);
            Callback done = a.callback(2);
            double timeout = opts.get("timeout") instanceof Number n ? n.doubleValue() : 10;
            if (!(timeout > 0 && timeout <= 60)) throw new IllegalArgumentException("timeout must be between 0 and 60 seconds");
            Object body = opts.get("body");
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis((long) (timeout * 1000)))
                    .method(String.valueOf(opts.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT),
                            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
            if (opts.get("headers") instanceof Map<?, ?> headers) headers.forEach((k, v) -> request.header(k.toString(), v.toString()));
            synchronized (HttpApi.class) {
                if (client == null) client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
            }
            client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(HttpApi::result)
                    .orTimeout((long) (timeout * 1000) + 5000, TimeUnit.MILLISECONDS) // the request timeout stops at the headers
                    .whenComplete((result, error) -> {
                        if (error == null) done.invoke(null, result);
                        else done.invoke(message(error));
                    });
            return null;
        });
    }

    private static Map<String, Object> result(HttpResponse<InputStream> response) {
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_BODY + 1);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (body.length > MAX_BODY) throw new IllegalStateException("the response is larger than " + (MAX_BODY >> 20) + " MiB");
        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", (long) response.statusCode());
        result.put("body", new String(body, StandardCharsets.UTF_8));
        result.put("headers", headers);
        return result;
    }

    private static String message(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException || error instanceof java.io.UncheckedIOException))
            error = error.getCause();
        if (error instanceof java.util.concurrent.TimeoutException || error instanceof java.net.http.HttpTimeoutException) return "timed out";
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
}
