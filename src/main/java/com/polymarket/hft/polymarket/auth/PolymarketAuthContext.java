package com.polymarket.hft.polymarket.auth;

import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;
import com.polymarket.hft.polymarket.model.ApiCreds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

@Component
public class PolymarketAuthContext {

  private static final Logger log = LoggerFactory.getLogger(PolymarketAuthContext.class);
  private static final Pattern HEX_32_BYTES = Pattern.compile("0x[0-9a-fA-F]{64}");
  private static final Pattern HEX_20_BYTES = Pattern.compile("0x[0-9a-fA-F]{40}");

  private final HftProperties properties;
  private final PolymarketClobClient clobClient;

  private volatile Credentials signerCredentials;
  private volatile ApiCreds apiCreds;

  public PolymarketAuthContext(HftProperties properties, PolymarketClobClient clobClient) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clobClient = Objects.requireNonNull(clobClient, "clobClient");
  }

  @PostConstruct
  void initFromConfig() {
    HftProperties.Auth auth = properties.getPolymarket().getAuth();

    String privateKey = auth.getPrivateKey();
    if (privateKey != null && !privateKey.isBlank()) {
      requireHex32("hft.polymarket.auth.private-key", privateKey);
      this.signerCredentials = Credentials.create(strip0x(privateKey));
    }

    String apiKey = auth.getApiKey();
    String apiSecret = auth.getApiSecret();
    String apiPassphrase = auth.getApiPassphrase();
    if (apiKey != null && !apiKey.isBlank()
        && apiSecret != null && !apiSecret.isBlank()
        && apiPassphrase != null && !apiPassphrase.isBlank()) {
      this.apiCreds = new ApiCreds(apiKey, apiSecret, apiPassphrase);
    }

    if (properties.getMode() == HftProperties.TradingMode.LIVE
        && auth.isAutoCreateOrDeriveApiCreds()
        && this.apiCreds == null) {
      Credentials signer = requireSignerCredentials();
      long nonce = auth.getNonce();
      ApiCreds derived = clobClient.createOrDeriveApiCreds(signer, nonce);
      this.apiCreds = derived;
      log.info("Loaded Polymarket API key creds (key={})", derived.key());
    }

    String funder = auth.getFunderAddress();
    if (funder != null && !funder.isBlank()) {
      requireHex20("hft.polymarket.auth.funder-address", funder);
    }
  }

  public Optional<Credentials> signerCredentials() {
    return Optional.ofNullable(signerCredentials);
  }

  public Credentials requireSignerCredentials() {
    Credentials creds = signerCredentials;
    if (creds == null) {
      throw new IllegalStateException("Polymarket signer private key is not configured (hft.polymarket.auth.private-key)");
    }
    return creds;
  }

  public Optional<ApiCreds> apiCreds() {
    return Optional.ofNullable(apiCreds);
  }

  public ApiCreds requireApiCreds() {
    ApiCreds creds = apiCreds;
    if (creds == null) {
      throw new IllegalStateException("Polymarket API creds not configured (api-key/secret/passphrase)");
    }
    return creds;
  }

  private static String strip0x(String hex) {
    String trimmed = hex.trim();
    return trimmed.startsWith("0x") || trimmed.startsWith("0X") ? trimmed.substring(2) : trimmed;
  }

  private static void requireHex32(String field, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!HEX_32_BYTES.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(field + " must be 0x + 64 hex chars");
    }
  }

  private static void requireHex20(String field, String value) {
    String trimmed = value == null ? "" : value.trim();
    if (!HEX_20_BYTES.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(field + " must be 0x + 40 hex chars");
    }
  }
}
