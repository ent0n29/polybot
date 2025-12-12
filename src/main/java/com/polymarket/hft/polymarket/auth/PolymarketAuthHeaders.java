package com.polymarket.hft.polymarket.auth;

import com.polymarket.hft.polymarket.crypto.Eip712Signer;
import com.polymarket.hft.polymarket.crypto.PolyHmacSigner;
import com.polymarket.hft.polymarket.model.ApiCreds;
import org.springframework.http.HttpMethod;
import org.web3j.crypto.Credentials;

import java.util.Map;
import java.util.Objects;

public final class PolymarketAuthHeaders {

  private PolymarketAuthHeaders() {
  }

  public static Map<String, String> l1(
      Credentials signingCredentials,
      int chainId,
      long timestampSeconds,
      long nonce
  ) {
    Objects.requireNonNull(signingCredentials, "signingCredentials");
    String address = signingCredentials.getAddress();
    String signature = Eip712Signer.signClobAuth(signingCredentials, chainId, timestampSeconds, nonce);

    return Map.of(
        "POLY_ADDRESS", address,
        "POLY_SIGNATURE", signature,
        "POLY_TIMESTAMP", Long.toString(timestampSeconds),
        "POLY_NONCE", Long.toString(nonce)
    );
  }

  public static Map<String, String> l2(
      Credentials signingCredentials,
      ApiCreds creds,
      long timestampSeconds,
      HttpMethod method,
      String requestPath,
      String body
  ) {
    Objects.requireNonNull(signingCredentials, "signingCredentials");
    Objects.requireNonNull(creds, "creds");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(requestPath, "requestPath");

    String address = signingCredentials.getAddress();
    String sig = PolyHmacSigner.sign(creds.secret(), timestampSeconds, method.name(), requestPath, body);

    return Map.of(
        "POLY_ADDRESS", address,
        "POLY_SIGNATURE", sig,
        "POLY_TIMESTAMP", Long.toString(timestampSeconds),
        "POLY_API_KEY", creds.key(),
        "POLY_PASSPHRASE", creds.passphrase()
    );
  }
}

