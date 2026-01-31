package nl.amony.modules.auth.api

import java.io.{ByteArrayInputStream, File, IOException}
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Path
import java.security.cert.{CertificateException, CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{GeneralSecurityException, KeyFactory, KeyStore, KeyStoreException}
import java.util
import java.util.Base64
import java.util.regex.Pattern
import java.util.regex.Pattern.CASE_INSENSITIVE
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.spec.PBEKeySpec
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKeyFactory}
import scala.io.Codec
import scala.jdk.CollectionConverters.*
import scala.util.Try

/*
 * Adapted from: https://gist.github.com/dain/29ce5c135796c007f9ec88e82ab21822
 */
object PemReader {

  private val CERT_PATTERN = Pattern.compile(
    "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
      "([a-z0-9+/=\\r\\n]+)" +                          // Base64 text
      "-+END\\s+.*CERTIFICATE[^-]*-+",                  // Footer
    CASE_INSENSITIVE
  )

  private val KEY_PATTERN = Pattern
    .compile("-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + "([a-z0-9+/=\\r\\n]+)" + "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+", CASE_INSENSITIVE)

  @throws[IOException]
  @throws[GeneralSecurityException]
  def loadTrustStore(certificateChainFile: Path): KeyStore = {
    val keyStore         = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    val certificateChain = readCertificateChain(certificateChainFile)
    for certificate <- certificateChain do {
      val principal = certificate.getSubjectX500Principal
      keyStore.setCertificateEntry(principal.getName("RFC2253"), certificate)
    }
    keyStore
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  def loadKeyStore(certificateChainFile: Path, privateKeyFile: Path, keyPassword: Option[String]): KeyStore = {
    val encodedKeySpec = readPrivateKey(privateKeyFile, keyPassword)

    val key = Try(KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec))
      .getOrElse(KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec))

    val certificateChain = readCertificateChain(certificateChainFile)

    if certificateChain.isEmpty then
      throw new CertificateException("Certificate chain file does not contain any certificates: " + certificateChainFile)

    val keyStore = KeyStore.getInstance("JKS")

    keyStore.load(null, null)

    keyStore.setKeyEntry("key", key, keyPassword.getOrElse("").toCharArray, certificateChain.toArray)
    keyStore
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  private def readCertificateChain(certificateChainFile: Path): List[X509Certificate] = {
    val contents           = scala.io.Source.fromFile(certificateChainFile.toFile)(using new Codec(US_ASCII)).mkString
    val matcher            = CERT_PATTERN.matcher(contents)
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val certificates       = new util.ArrayList[X509Certificate]
    var start              = 0
    while matcher.find(start) do {
      val buffer = base64Decode(matcher.group(1))
      val input  = new ByteArrayInputStream(buffer)
      certificates.add(certificateFactory.generateCertificate(input).asInstanceOf[X509Certificate])
      start = matcher.end
    }
    certificates.asScala.toList
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  private def readPrivateKey(keyFile: Path, keyPassword: Option[String]): PKCS8EncodedKeySpec = {
    val content = scala.io.Source.fromFile(keyFile.toFile)(using new Codec(US_ASCII)).mkString
    val matcher = KEY_PATTERN.matcher(content)
    if !matcher.find then throw new KeyStoreException("No private key found: " + keyFile)

    val encodedKey = base64Decode(matcher.group(1))

    if !keyPassword.isDefined then return new PKCS8EncodedKeySpec(encodedKey)

    val encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey)
    val keyFactory              = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName)
    val secretKey               = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get.toCharArray))
    val cipher                  = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName)

    cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters)
    encryptedPrivateKeyInfo.getKeySpec(cipher)
  }

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

}
