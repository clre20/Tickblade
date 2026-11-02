package clre20.tickBlade.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 向 GitHub Releases 檢查最新正式版本。
 * 結果快取 10 分鐘；不定時輪詢；只用一條背景執行緒。
 */
public final class UpdateChecker {

    public enum State { NEWER, LATEST, DEV, FAILED }

    /** reasonKey：messages.yml 的 version.reason-* ，code 為 HTTP 狀態碼 */
    public record Result(State state, String latest, String url, String reasonKey, int code) {
    }

    private static final String API = "https://api.github.com/repos/clre20/TickBlade/releases/latest";
    private static final String PAGE = "https://github.com/clre20/TickBlade/releases/latest";
    private static final long CACHE_MS = 10 * 60 * 1000L;

    private final String currentVersion;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TickBlade-UpdateChecker");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Result cached;
    private volatile long cachedAt;
    private CompletableFuture<Result> inFlight;

    public UpdateChecker(String currentVersion) {
        this.currentVersion = strip(currentVersion);
    }

    public Result cached() {
        Result r = cached;
        return r != null && System.currentTimeMillis() - cachedAt < CACHE_MS ? r : null;
    }

    /** 最近一次的結果（不管是否過期），給登入提醒用 */
    public Result last() {
        return cached;
    }

    public synchronized CompletableFuture<Result> check() {
        Result c = cached();
        if (c != null) return CompletableFuture.completedFuture(c);
        if (inFlight != null && !inFlight.isDone()) return inFlight;
        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TickBlade/" + currentVersion)
                .GET().build();
        inFlight = client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parse)
                .exceptionally(e -> new Result(State.FAILED, null, PAGE, "version.reason-network", 0))
                .thenApply(r -> {
                    cached = r;
                    cachedAt = System.currentTimeMillis();
                    return r;
                });
        return inFlight;
    }

    private Result parse(HttpResponse<String> resp) {
        if (resp.statusCode() == 404) return new Result(State.FAILED, null, PAGE, "version.reason-no-release", 404);
        if (resp.statusCode() != 200) return new Result(State.FAILED, null, PAGE, "version.reason-http", resp.statusCode());
        try {
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            String tag = strip(o.get("tag_name").getAsString());
            String url = o.has("html_url") ? o.get("html_url").getAsString() : PAGE;
            if (!url.startsWith("https://github.com/")) url = PAGE;
            int cmp = compare(tag, currentVersion);
            State s = cmp > 0 ? State.NEWER : cmp == 0 ? State.LATEST : State.DEV;
            return new Result(s, tag, url, null, 200);
        } catch (RuntimeException e) {
            return new Result(State.FAILED, null, PAGE, "version.reason-http", resp.statusCode());
        }
    }

    public String currentVersion() {
        return currentVersion;
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String strip(String v) {
        if (v == null) return "0";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    /** 逐段比對數字（1.10.0 > 1.9.9），非數字部分視為 0 */
    static int compare(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
