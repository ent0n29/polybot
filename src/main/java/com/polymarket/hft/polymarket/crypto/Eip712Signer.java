package com.polymarket.hft.polymarket.crypto;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import lombok.experimental.UtilityClass;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@UtilityClass
public class Eip712Signer {

  public static String signClobAuth(Credentials credentials, int chainId, long timestampSeconds, long nonce) {
    String address = credentials.getAddress();

    byte[] domainSeparator = hashStruct(
        Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId)".getBytes(StandardCharsets.UTF_8)
        ),
        hashString("ClobAuthDomain"),
        hashString("1"),
        uint256(BigInteger.valueOf(chainId))
    );

    byte[] messageHash = hashStruct(
        Hash.sha3(
            "ClobAuth(address address,string timestamp,uint256 nonce,string message)".getBytes(StandardCharsets.UTF_8)
        ),
        address(address),
        hashString(Long.toString(timestampSeconds)),
        uint256(BigInteger.valueOf(nonce)),
        hashString("This message attests that I control the given wallet")
    );

    byte[] digest = eip712Digest(domainSeparator, messageHash);
    return signDigest(credentials, digest);
  }

  public static String signOrder(
      Credentials credentials,
      int chainId,
      String verifyingContract,
      String salt,
      String maker,
      String signer,
      String taker,
      String tokenId,
      String makerAmount,
      String takerAmount,
      String expiration,
      String nonce,
      String feeRateBps,
      int side,
      int signatureType
  ) {
    byte[] domainSeparator = hashStruct(
        Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8)
        ),
        hashString("Polymarket CTF Exchange"),
        hashString("1"),
        uint256(BigInteger.valueOf(chainId)),
        address(verifyingContract)
    );

    byte[] messageHash = hashStruct(
        Hash.sha3(
            ("Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,"
                + "uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)")
                .getBytes(StandardCharsets.UTF_8)
        ),
        uint256(new BigInteger(salt)),
        address(maker),
        address(signer),
        address(taker),
        uint256(new BigInteger(tokenId)),
        uint256(new BigInteger(makerAmount)),
        uint256(new BigInteger(takerAmount)),
        uint256(new BigInteger(expiration)),
        uint256(new BigInteger(nonce)),
        uint256(new BigInteger(feeRateBps)),
        uint256(BigInteger.valueOf(side)),
        uint256(BigInteger.valueOf(signatureType))
    );

    byte[] digest = eip712Digest(domainSeparator, messageHash);
    return signDigest(credentials, digest);
  }

  private static String signDigest(Credentials credentials, byte[] digest32) {
    Sign.SignatureData signatureData = Sign.signMessage(digest32, credentials.getEcKeyPair(), false);
    byte v = signatureData.getV()[0];
    if (v == 0 || v == 1) {
      v = (byte) (v + 27);
    }

    byte[] sig = new byte[65];
    System.arraycopy(signatureData.getR(), 0, sig, 0, 32);
    System.arraycopy(signatureData.getS(), 0, sig, 32, 32);
    sig[64] = v;
    return "0x" + Numeric.toHexStringNoPrefix(sig);
  }

  private static byte[] eip712Digest(byte[] domainSeparator, byte[] messageHash) {
    ByteBuffer buf = ByteBuffer.allocate(2 + 32 + 32);
    buf.put((byte) 0x19);
    buf.put((byte) 0x01);
    buf.put(domainSeparator);
    buf.put(messageHash);
    return Hash.sha3(buf.array());
  }

  private static byte[] hashStruct(byte[] typeHash, byte[]... encodedFields) {
    int totalLen = 32 + 32 * encodedFields.length;
    ByteBuffer buf = ByteBuffer.allocate(totalLen);
    buf.put(typeHash);
    for (byte[] field : encodedFields) {
      if (field.length != 32) {
        throw new IllegalArgumentException("Expected 32-byte encoded field, got " + field.length);
      }
      buf.put(field);
    }
    return Hash.sha3(buf.array());
  }

  private static byte[] hashString(String value) {
    return Hash.sha3(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] uint256(BigInteger value) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("uint256 cannot be negative");
    }
    return Numeric.toBytesPadded(value, 32);
  }

  private static byte[] address(String address) {
    byte[] addr = Numeric.hexStringToByteArray(address == null ? "" : address.trim());
    if (addr.length != 20) {
      throw new IllegalArgumentException("Expected 20-byte address, got " + addr.length);
    }
    byte[] padded = new byte[32];
    System.arraycopy(addr, 0, padded, 12, 20);
    return padded;
  }
}
