package dev.automodpack4paper;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Lets the admin use a certificate signed by a public CA (e.g. Let's Encrypt) instead of the self-signed one.
 * Clients trust CA-signed certificates through the JVM trust store, so players get no trust prompt.
 * The files are copied into core's own cert/key locations (key converted to PKCS#8, which core needs).
 */
public final class TlsImporter {

    private TlsImporter() {}

    public static boolean configured(String certFile, String keyFile) {
        return !certFile.isBlank() || !keyFile.isBlank();
    }

    /** Copies chain + key into core's files. Returns null on success, else an error message. */
    public static String apply(Logger log, String certFile, String keyFile, Path coreCert, Path coreKey, Path marker) {
        if (certFile.isBlank() != keyFile.isBlank()) {
            return "tls.certificate-file and tls.private-key-file must both be set (or both empty).";
        }
        try {
            if (certFile.isBlank()) { // back to self-signed: drop a previously imported pair
                if (Files.deleteIfExists(marker)) {
                    Files.deleteIfExists(coreCert);
                    Files.deleteIfExists(coreKey);
                    log.info("TLS import removed; a self-signed certificate will be generated.");
                }
                return null;
            }

            Path chainPath = Path.of(certFile);
            Path keyPath = Path.of(keyFile);
            if (!Files.isRegularFile(chainPath)) return "tls.certificate-file not found: " + chainPath;
            if (!Files.isRegularFile(keyPath)) return "tls.private-key-file not found: " + keyPath;

            String chainPem = Files.readString(chainPath, StandardCharsets.UTF_8);
            List<X509Certificate> chain;
            try (InputStream in = Files.newInputStream(chainPath)) {
                chain = CertificateFactory.getInstance("X.509").generateCertificates(in).stream().map(c -> (X509Certificate) c).toList();
            }
            if (chain.isEmpty()) return "No certificate found in " + chainPath;

            X509Certificate leaf = chain.get(0);
            Instant end = leaf.getNotAfter().toInstant();
            if (end.isBefore(Instant.now())) return "The certificate in " + chainPath + " has EXPIRED (" + end + ").";
            long days = Duration.between(Instant.now(), end).toDays();
            if (days < 14) log.warning("The TLS certificate expires in " + days + " days.");
            if (chain.size() == 1 && leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
                log.warning("The imported certificate is self-signed; players will still get a trust prompt.");
            }

            PrivateKey key = readKey(keyPath);
            if (!matches(leaf, key)) return "The private key does not belong to the first certificate in " + chainPath + ".";

            Files.createDirectories(coreCert.getParent());
            Files.writeString(coreCert, chainPem);
            Files.writeString(coreKey, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
            Files.writeString(marker, "imported from " + chainPath);
            log.info("Using imported TLS certificate for " + leaf.getSubjectX500Principal() + " (valid until " + end + ").");
            return null;
        } catch (Exception e) {
            return "TLS import failed: " + e;
        }
    }

    private static PrivateKey readKey(Path file) throws Exception {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8); PEMParser parser = new PEMParser(r)) {
            Object o = parser.readObject();
            var conv = new JcaPEMKeyConverter();
            if (o instanceof PEMKeyPair kp) return conv.getPrivateKey(kp.getPrivateKeyInfo());
            if (o instanceof PrivateKeyInfo pki) return conv.getPrivateKey(pki);
            throw new IllegalArgumentException("Unsupported key file (encrypted or not PEM?): " + file);
        }
    }

    private static boolean matches(X509Certificate leaf, PrivateKey key) throws Exception {
        String alg = switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "EdDSA", "Ed25519" -> "Ed25519";
            default -> "SHA256withRSA";
        };
        byte[] data = "am4p".getBytes(StandardCharsets.UTF_8);
        var signer = java.security.Signature.getInstance(alg);
        signer.initSign(key);
        signer.update(data);
        byte[] sig = signer.sign();
        var verifier = java.security.Signature.getInstance(alg);
        verifier.initVerify(leaf.getPublicKey());
        verifier.update(data);
        return verifier.verify(sig);
    }
}
