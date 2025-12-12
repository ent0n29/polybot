package com.polymarket.hft.polymarket.http;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class HttpRequestFactory {

  private final URI baseUri;

  public HttpRequestFactory(URI baseUri) {
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
  }

  public HttpRequest.Builder request(String path, Map<String, String> query) {
    return HttpRequest.newBuilder(buildUri(path, query));
  }

  private URI buildUri(String path, Map<String, String> query) {
    StringBuilder sb = new StringBuilder(baseUri.toString());
    if (sb.charAt(sb.length() - 1) == '/' && path.startsWith("/")) {
      sb.setLength(sb.length() - 1);
    }
    sb.append(path);

    if (query != null && !query.isEmpty()) {
      sb.append("?");
      sb.append(query.entrySet().stream()
          .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
          .reduce((a, b) -> a + "&" + b)
          .orElse(""));
    }
    return URI.create(sb.toString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

