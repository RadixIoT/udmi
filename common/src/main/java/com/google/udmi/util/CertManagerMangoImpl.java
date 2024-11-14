package com.google.udmi.util;

import static com.google.udmi.util.GeneralUtils.sha256;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import udmi.schema.EndpointConfiguration.Transport;

/**
 * Mango Implementation Manager class for CA-signed SSL certificates.
 */
public class CertManagerMangoImpl implements CertManagerIntf {

  private final char[] password;
  private X509Certificate caCertificateByteArray;
  private X509Certificate clientCertificateByteArray;
  private PrivateKey rsaPrivateKey;
  private byte[] rsaPublicKeyByteArray;

  private final boolean isSsl;

  private boolean isVerifyHostname;

  {
    Security.addProvider(new BouncyCastleProvider());
  }

  public CertManagerMangoImpl(X509Certificate caCertificate, X509Certificate clientCertificate,
      PrivateKey rsaPrivateKey, String rsaCertificate,
      Transport transport, String passString, boolean isVerifyHostname, Consumer<String> logging) {
    this.caCertificateByteArray = caCertificate;
    this.clientCertificateByteArray = clientCertificate;
    this.rsaPublicKeyByteArray = convertToByteArray(rsaCertificate);
    this.rsaPrivateKey = rsaPrivateKey;
    this.password = passString.toCharArray();
    this.isVerifyHostname = isVerifyHostname;
    isSsl = Transport.SSL.equals(transport);
    logging.accept("CA cert : " + CA_CERT_ALIAS);
    logging.accept("Device cert : " + CLIENT_CERT_ALIAS);
    logging.accept("Private key : " + PRIVATE_KEY_ALIAS);
    logging.accept("Password sha256 " + sha256(passString).substring(0, 8));
  }

  @Override
  public SSLSocketFactory getCertSocketFactory() throws Exception {
    final X509Certificate caCert = caCertificateByteArray;
    final X509Certificate clientCert = clientCertificateByteArray;
    final PrivateKey privateKey = rsaPrivateKey;
    return createSslContext(caCert, clientCert, privateKey, password).getSocketFactory();
  }

  @Override
  public SocketFactory getSocketFactory() {
    try {
      if (!isSsl) {
        return SocketFactory.getDefault();
      }
      return getCertSocketFactory();
    } catch (Exception e) {
      throw new RuntimeException("While creating SSL socket factory", e);
    }
  }

  private byte[] convertToByteArray(String certificate) {
    return certificate.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean isVerifyHostname() {
    return isVerifyHostname;
  }

}