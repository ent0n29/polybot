package com.polymarket.hft.polymarket.http;

import java.net.http.HttpRequest;
import java.util.Map;

import lombok.experimental.UtilityClass;

@UtilityClass
public class HttpHeadersUtil {

  public static void apply(HttpRequest.Builder builder, Map<String, String> headers) {
    String[] flat = flatten(headers);
    if (flat.length == 0) {
      return;
    }
    builder.headers(flat);
  }

  public static String[] flatten(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return new String[0];
    }
    String[] flat = new String[headers.size() * 2];
    int i = 0;
    for (Map.Entry<String, String> e : headers.entrySet()) {
      flat[i++] = e.getKey();
      flat[i++] = e.getValue();
    }
    return flat;
  }
}
