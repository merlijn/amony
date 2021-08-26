package io.amony.http

import java.io._
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{GeneralSecurityException, KeyFactory, KeyStore, KeyStoreException}
import java.security.cert.{CertificateException, CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.util
import java.util.{Base64, Optional}
import java.util.regex.Pattern
import java.util.regex.Pattern.CASE_INSENSITIVE
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.{Cipher, EncryptedPrivateKeyInfo, SecretKeyFactory}
import javax.crypto.spec.PBEKeySpec
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Try

/*
 * Copied from: https://gist.github.com/dain/29ce5c135796c007f9ec88e82ab21822
 *
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
object PemReader {

  private val CERT_PATTERN = Pattern.compile(
    "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
    "([a-z0-9+/=\\r\\n]+)" + // Base64 text
    "-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
    CASE_INSENSITIVE)

  private val KEY_PATTERN = Pattern.compile(
    "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" +
      "([a-z0-9+/=\\r\\n]+)" +
      "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+", CASE_INSENSITIVE)

  @throws[IOException]
  @throws[GeneralSecurityException]
  def loadTrustStore(certificateChainFile: File): KeyStore = {
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, null)
    val certificateChain = readCertificateChain(certificateChainFile)
    for (certificate <- certificateChain) {
      val principal = certificate.getSubjectX500Principal
      keyStore.setCertificateEntry(principal.getName("RFC2253"), certificate)
    }
    keyStore
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  def loadKeyStore(certificateChainFile: File, privateKeyFile: File, keyPassword: Option[String]): KeyStore = {
    val encodedKeySpec = readPrivateKey(privateKeyFile, keyPassword)

    val key = Try {
      KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec)
    }.getOrElse {
      KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec)
    }

    val certificateChain = readCertificateChain(certificateChainFile)

    if (certificateChain.isEmpty)
      throw new CertificateException("Certificate file does not contain any certificates: " + certificateChainFile)

    val keyStore = KeyStore.getInstance("JKS")

    keyStore.load(null, null)

    keyStore.setKeyEntry("key", key, keyPassword.getOrElse("").toCharArray, certificateChain.toArray)
    keyStore
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  private def readCertificateChain(certificateChainFile: File): List[X509Certificate] = {
    val contents = readFile(certificateChainFile)
    val matcher = CERT_PATTERN.matcher(contents)
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val certificates = new util.ArrayList[X509Certificate]
    var start = 0
    while ( {
      matcher.find(start)
    }) {
      val buffer = base64Decode(matcher.group(1))
      certificates.add(certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)).asInstanceOf[X509Certificate])
      start = matcher.end
    }
    certificates.asScala.toList
  }

  @throws[IOException]
  @throws[GeneralSecurityException]
  private def readPrivateKey(keyFile: File, keyPassword: Option[String]): PKCS8EncodedKeySpec = {
    val content = readFile(keyFile)
    val matcher = KEY_PATTERN.matcher(content)
    if (!matcher.find)
      throw new KeyStoreException("found no private key: " + keyFile)

    val encodedKey = base64Decode(matcher.group(1))

    if (!keyPassword.isDefined)
      return new PKCS8EncodedKeySpec(encodedKey)

    val encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey)
    val keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName)
    val secretKey = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get.toCharArray))
    val cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName)

    cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters)
    encryptedPrivateKeyInfo.getKeySpec(cipher)
  }

  private def base64Decode(base64: String): Array[Byte] = Base64.getMimeDecoder.decode(base64.getBytes(US_ASCII))

  @throws[IOException]
  private def readFile(file: File): String = try {
    val reader = new InputStreamReader(new FileInputStream(file), US_ASCII)
    try {
      val stringBuilder = new StringBuilder
      val buffer = CharBuffer.allocate(2048)
      while ( {
        reader.read(buffer) != -1
      }) {
        buffer.flip
        stringBuilder.append(buffer)
        buffer.clear
      }
      stringBuilder.toString
    } finally if (reader != null) reader.close()
  }
}
