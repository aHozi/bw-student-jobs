package de.bwjobs.scan;

import de.bwjobs.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for career pages: honest User-Agent, robots.txt, per-host delay,
 * retries with exponential backoff on 429/5xx and conditional GET (ETag / Last-Modified).
 */
@Component
public class PoliteHttpClient {
    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);

    private final HttpClient http;
    private final AppProperties.Scan cfg;
    private final Map<String, Long> nextAllowedByHost = new ConcurrentHashMap<>();
    private final Map<String, RobotsRules> robotsByHost = new ConcurrentHashMap<>();
    private final Map<String, CachedResponse> conditionalCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> e) {
                    return size() > 500;
                }
            });

    private record CachedResponse(String etag, String lastModified, FetchResult result) {}

    public PoliteHttpClient(AppProperties props) {
        this.cfg = props.scan();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public FetchResult get(String url) {
        return get(url, Map.of());
    }

    public FetchResult get(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        checkRobots(uri);
        HttpRequest.Builder b = baseRequest(uri, headers).GET();
        CachedResponse cached = conditionalCache.get(url);
        if (cached != null) {
            if (cached.etag() != null) b.header("If-None-Match", cached.etag());
            if (cached.lastModified() != null) b.header("If-Modified-Since", cached.lastModified());
        }
        HttpResponse<String> resp = send(uri, b.build());
        if (resp.statusCode() == 304 && cached != null) return cached.result();
        FetchResult result = toResult(resp);
        if (result.ok()) {
            String etag = resp.headers().firstValue("ETag").orElse(null);
            String lm = resp.headers().firstValue("Last-Modified").orElse(null);
            if (etag != null || lm != null) conditionalCache.put(url, new CachedResponse(etag, lm, result));
        }
        return result;
    }

    public FetchResult postJson(String url, String json) {
        URI uri = URI.create(url);
        checkRobots(uri);
        HttpRequest req = baseRequest(uri, Map.of("Content-Type", "application/json", "Accept", "application/json"))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return toResult(send(uri, req));
    }

    private HttpRequest.Builder baseRequest(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds()))
                .header("User-Agent", cfg.userAgent())
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
        if (!headers.containsKey("Accept")) b.header("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8");
        headers.forEach(b::header);
        return b;
    }

    private HttpResponse<String> send(URI uri, HttpRequest req) {
        int attempts = 0;
        long backoffMs = 2000;
        while (true) {
            attempts++;
            waitForHost(uri.getHost());
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int s = resp.statusCode();
                if ((s == 429 || s >= 500) && attempts < 4) {
                    long wait = resp.headers().firstValue("Retry-After").map(this::parseRetryAfter).orElse(backoffMs);
                    log.debug("HTTP {} for {} - retrying in {} ms", s, uri, wait);
                    sleep(Math.min(wait, 60_000));
                    backoffMs *= 2;
                    continue;
                }
                return resp;
            } catch (IOException e) {
                if (attempts >= 3) throw new FetchException("Network error for " + uri + ": " + e.getMessage(), e);
                sleep(backoffMs);
                backoffMs *= 2;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted", e);
            }
        }
    }

    private long parseRetryAfter(String v) {
        try {
            return Long.parseLong(v.trim()) * 1000;
        } catch (NumberFormatException e) {
            return 5000;
        }
    }

    private FetchResult toResult(HttpResponse<String> resp) {
        return new FetchResult(resp.statusCode(), resp.body(), resp.uri().toString(),
                resp.headers().firstValue("Content-Type").orElse(""));
    }

    /** Reserves the next free slot for this host, then sleeps until it. Safe across worker threads. */
    private void waitForHost(String host) {
        long delay = cfg.perHostDelayMs();
        long slot;
        synchronized (nextAllowedByHost) {
            long now = System.currentTimeMillis();
            slot = Math.max(now, nextAllowedByHost.getOrDefault(host, 0L));
            nextAllowedByHost.put(host, slot + delay);
        }
        long sleepMs = slot - System.currentTimeMillis();
        if (sleepMs > 0) sleep(sleepMs);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted", e);
        }
    }

    // ---------------- robots.txt ----------------

    private void checkRobots(URI uri) {
        String host = uri.getScheme() + "://" + uri.getAuthority();
        RobotsRules rules = robotsByHost.computeIfAbsent(host, this::loadRobots);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        if (!rules.allowed(path)) {
            throw new FetchException("Blocked by robots.txt: " + uri, 999);
        }
    }

    private RobotsRules loadRobots(String hostBase) {
        try {
            URI robotsUri = URI.create(hostBase + "/robots.txt");
            waitForHost(robotsUri.getHost());
            HttpRequest req = baseRequest(robotsUri, Map.of("Accept", "text/plain")).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) return RobotsRules.parse(resp.body(), cfg.userAgent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("robots.txt not available for {}: {}", hostBase, e.getMessage());
        }
        return RobotsRules.ALLOW_ALL;
    }

    /** Minimal robots.txt parser: groups for our agent or '*', Allow/Disallow with longest-match wins. */
    record RobotsRules(List<String> allow, List<String> disallow) {
        static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), List.of());

        static RobotsRules parse(String body, String userAgent) {
            String ourAgent = userAgent.split("/")[0].toLowerCase(Locale.ROOT);
            List<String> allowOurs = new ArrayList<>(), disallowOurs = new ArrayList<>();
            List<String> allowAll = new ArrayList<>(), disallowAll = new ArrayList<>();
            boolean inOurs = false, inAll = false, lastWasAgent = false;
            for (String raw : body.split("\\R")) {
                String line = raw.replaceAll("#.*", "").trim();
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (key.equals("user-agent")) {
                    if (!lastWasAgent) { inOurs = false; inAll = false; }
                    String ua = value.toLowerCase(Locale.ROOT);
                    if (ua.equals("*")) inAll = true;
                    else if (ourAgent.contains(ua) || ua.contains(ourAgent)) inOurs = true;
                    lastWasAgent = true;
                    continue;
                }
                lastWasAgent = false;
                if (key.equals("allow") && !value.isEmpty()) {
                    if (inOurs) allowOurs.add(value);
                    if (inAll) allowAll.add(value);
                } else if (key.equals("disallow") && !value.isEmpty()) {
                    if (inOurs) disallowOurs.add(value);
                    if (inAll) disallowAll.add(value);
                }
            }
            if (!allowOurs.isEmpty() || !disallowOurs.isEmpty()) return new RobotsRules(allowOurs, disallowOurs);
            return new RobotsRules(allowAll, disallowAll);
        }

        boolean allowed(String path) {
            int bestAllow = longestMatch(allow, path);
            int bestDisallow = longestMatch(disallow, path);
            return bestDisallow < 0 || bestAllow >= bestDisallow;
        }

        private static int longestMatch(List<String> rules, String path) {
            int best = -1;
            for (String r : rules) {
                if (matches(r, path) && r.length() > best) best = r.length();
            }
            return best;
        }

        private static boolean matches(String rule, String path) {
            boolean anchored = rule.endsWith("$");
            String r = anchored ? rule.substring(0, rule.length() - 1) : rule;
            String regex = Arrays.stream(r.split("\\*", -1)).map(java.util.regex.Pattern::quote)
                    .reduce((a, b) -> a + ".*" + b).orElse("");
            return path.matches(regex + (anchored ? "" : ".*"));
        }
    }
}
