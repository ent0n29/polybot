package com.polymarket.hft.polymarket.web;

import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.auth.PolymarketAuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/polymarket/auth")
@RequiredArgsConstructor
public class PolymarketAuthController {

  private final PolymarketAuthContext authContext;
  private final HftProperties properties;

  @GetMapping("/status")
  public ResponseEntity<PolymarketAuthStatusResponse> status() {
    HftProperties.Auth auth = properties.polymarket().auth();
    return ResponseEntity.ok(new PolymarketAuthStatusResponse(
        authContext.signerCredentials().isPresent(),
        authContext.signerCredentials().map(c -> c.getAddress()).orElse(null),
        authContext.apiCreds().isPresent(),
        auth.autoCreateOrDeriveApiCreds(),
        auth.nonce()
    ));
  }

  /**
   * Attempts to create/derive API creds and store them in-memory, without returning secrets.
   * In LIVE mode, requires header {@link LiveTradingGuardFilter#HEADER_LIVE_ACK}: true.
   */
  @PostMapping("/derive")
  public ResponseEntity<PolymarketDeriveCredsResponse> derive(
      @RequestHeader(name = LiveTradingGuardFilter.HEADER_LIVE_ACK, required = false) String liveAck,
      @RequestParam(name = "nonce", required = false) Long nonceOverride
  ) {
    if (properties.mode() == HftProperties.TradingMode.LIVE && !"true".equalsIgnoreCase(liveAck)) {
      return ResponseEntity.status(428).body(new PolymarketDeriveCredsResponse(
          false,
          false,
          null,
          nonceOverride == null ? properties.polymarket().auth().nonce() : nonceOverride,
          "Refusing LIVE credentials derive without " + LiveTradingGuardFilter.HEADER_LIVE_ACK + ": true"
      ));
    }

    long nonce = nonceOverride == null ? properties.polymarket().auth().nonce() : nonceOverride;
    PolymarketAuthContext.DeriveAttempt attempt = authContext.tryCreateOrDeriveApiCreds(nonce);
    return ResponseEntity.ok(new PolymarketDeriveCredsResponse(
        attempt.attempted(),
        attempt.success(),
        attempt.method(),
        attempt.nonce(),
        attempt.error()
    ));
  }
}
