package com.google.udmi.util;

import java.io.IOException;
import java.io.Reader;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

public interface CertManagerIntf {

  String CA_CERT_FILE = "ca.crt";
  String BOUNCY_CASTLE_PROVIDER = "BC";
  String TLS_1_2_PROTOCOL = "TLSv1.2";
  String X509_FACTORY = "X.509";
  String X509_ALGORITHM = "X509";
  String CA_CERT_ALIAS = "ca-certificate";
  String CLIENT_CERT_ALIAS = "certificate";
  String PRIVATE_KEY_ALIAS = "private-key";

  JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BOUNCY_CASTLE_PROVIDER);

  SSLSocketFactory getCertSocketFactory() throws Exception;

  SocketFactory getSocketFactory();

  default boolean isVerifyHostname() {
    return false;
  }

  default KeyStore createClientKeyStoreInstance(X509Certificate clientCert,
      PrivateKey privateKey, char[] password)
      throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
    KeyStore clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientKeyStore.load(null, null);
    clientKeyStore.setCertificateEntry(CLIENT_CERT_ALIAS, clientCert);
    clientKeyStore.setKeyEntry(PRIVATE_KEY_ALIAS, privateKey, password,
        new java.security.cert.Certificate[]{clientCert});
    return clientKeyStore;
  }

  default PrivateKey getPrivateKey(Reader rsaPrivateRead, char[] password) throws IOException {
    PEMParser pemParser = new PEMParser(rsaPrivateRead);
    Object pemObject = pemParser.readObject();
    if (pemObject instanceof PEMEncryptedKeyPair keyPair) {
      PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().build(password);
      return converter.getKeyPair(keyPair.decryptKeyPair(decProv)).getPrivate();
    } else if (pemObject instanceof PEMKeyPair keyPair) {
      return converter.getKeyPair(keyPair).getPrivate();
    } else if (pemObject instanceof PrivateKeyInfo keyPair) {
      return converter.getPrivateKey(keyPair);
    } else {
      throw new RuntimeException(String.format("Unknown pem file type %s from %s",
          pemObject.getClass().getSimpleName(), "rsaPrivateKey"));
    }
  }

  default TrustManagerFactory createTrustManagerFactory(X509Certificate caCert)
      throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
    KeyStore caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    caKeyStore.load(null, null);
    caKeyStore.setCertificateEntry(CA_CERT_ALIAS, caCert);
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(X509_ALGORITHM);
    trustManagerFactory.init(caKeyStore);
    return trustManagerFactory;
  }

  default SSLContext createSslContext(X509Certificate caCert, X509Certificate clientCert, PrivateKey privateKey, char[] password)
      throws CertificateException, KeyStoreException, IOException, NoSuchAlgorithmException, KeyManagementException, UnrecoverableKeyException {
    TrustManagerFactory trustManagerFactory = createTrustManagerFactory(caCert);
    KeyStore clientKeyStore = createClientKeyStoreInstance(clientCert, privateKey, password);
    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(clientKeyStore, password);
    SSLContext context = SSLContext.getInstance(TLS_1_2_PROTOCOL);
    context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return context;
  }

}