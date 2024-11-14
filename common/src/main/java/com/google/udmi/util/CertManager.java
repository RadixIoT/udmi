package com.google.udmi.util;

import static com.google.common.base.Preconditions.checkState;
import static com.google.udmi.util.GeneralUtils.sha256;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import udmi.schema.EndpointConfiguration.Transport;

/**
 * Manager class for CA-signed SSL certificates.
 */
public class CertManager implements CertManagerIntf {

  private final File caCrtFile;
  private final File keyFile;
  private final File crtFile;
  private final char[] password;
  private final boolean isSsl;

  {
    Security.addProvider(new BouncyCastleProvider());
  }

  /**
   * Create a new cert manager for the given site model and configuration.
   */
  public CertManager(File caCrtFile, File clientDir, Transport transport,
      String passString, Consumer<String> logging) {
    this.caCrtFile = caCrtFile;
    isSsl = Transport.SSL.equals(transport);

    if (isSsl) {
      String prefix = keyPrefix(clientDir);
      crtFile = new File(clientDir, prefix + "_private.crt");
      keyFile = new File(clientDir, prefix + "_private.pem");
      this.password = passString.toCharArray();
      logging.accept("CA cert file: " + caCrtFile);
      logging.accept("Device cert file: " + crtFile);
      logging.accept("Private key file: " + keyFile);
      logging.accept("Password sha256 " + sha256(passString).substring(0, 8));
    } else {
      crtFile = null;
      keyFile = null;
      password = null;
    }
  }

  private String keyPrefix(File clientDir) {
    File rsaCrtFile = new File(clientDir, "rsa_private.crt");
    File ecCrtFile = new File(clientDir, "ec_private.crt");
    checkState(rsaCrtFile.exists() || ecCrtFile.exists(),
        "no .crt found for device in " + clientDir.getAbsolutePath());
    return rsaCrtFile.exists() ? "rsa" : "ec";
  }

  /**
   * Get a certificate-backed socket factory.
   */
  @Override
  public SSLSocketFactory getCertSocketFactory() throws Exception {
    CertificateFactory certFactory = CertificateFactory.getInstance(X509_FACTORY);
    final X509Certificate caCert = loadX509Certificate(caCrtFile, certFactory);
    final X509Certificate clientCert = loadX509Certificate(crtFile, certFactory);
    final PrivateKey privateKey = getPrivateKey(new FileReader(keyFile), password);
    createSslContext(caCert, clientCert, privateKey, password);
    return createSslContext(caCert, clientCert, privateKey, password).getSocketFactory();
  }

  private X509Certificate loadX509Certificate(File certificateFile, CertificateFactory certFactory)
      throws FileNotFoundException, CertificateException {
    BufferedInputStream bis = new BufferedInputStream(new FileInputStream(certificateFile));
    return (X509Certificate) certFactory.generateCertificate(bis);
  }

  /**
   * Get a socket factory appropriate for the configuration.
   */
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
}
