package spiffe.svid.x509svid;

import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.commons.util.StringUtils;
import spiffe.exception.X509SvidException;
import spiffe.spiffeid.SpiffeId;
import spiffe.spiffeid.TrustDomain;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class X509SvidTest {

    static String keyRSA = "testdata/x509svid/key-pkcs8-rsa.pem";
    static String keyRSAOther = "testdata/x509svid/key-rsa-other.pem";
    static String certSingle = "testdata/x509svid/good-leaf-only.pem";
    static String leafNoDigitalSignature = "testdata/x509svid/wrong-leaf-no-digital-signature.pem";
    static String leafCRLSign = "testdata/x509svid/wrong-leaf-crl-sign.pem";
    static String leafCertSign = "testdata/x509svid/wrong-leaf-cert-sign.pem";
    static String leafCAtrue = "testdata/x509svid/wrong-leaf-ca-true.pem";
    static String leafEmptyID = "testdata/x509svid/wrong-leaf-empty-id.pem";
    static String signNoCA = "testdata/x509svid/wrong-intermediate-no-ca.pem";
    static String signNoKeyCertSign = "testdata/x509svid/wrong-intermediate-no-key-cert-sign.pem";
    static String keyECDSA = "testdata/x509svid/key-pkcs8-ecdsa.pem";
    static String certMultiple = "testdata/x509svid/good-leaf-and-intermediate.pem";
    static String corrupted = "testdata/x509svid/corrupted";
    static String keyECDSAOther = "testdata/x509svid/key-ecdsa-other.pem";

    static Stream<Arguments> provideX509SvidScenarios() {
        return Stream.of(
                Arguments.of(TestCase
                        .builder()
                        .name("1. Single certificate and key")
                        .certsPath(certSingle)
                        .keyPath(keyRSA)
                        .expectedSpiffeId(SpiffeId.of(TrustDomain.of("example.org"), "workload-1"))
                        .expectedNumberOfCerts(1)
                        .expectedPrivateKeyAlgorithm("RSA")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("2. Certificate with intermediate and key")
                        .certsPath(certMultiple)
                        .keyPath(keyECDSA)
                        .expectedSpiffeId(SpiffeId.of(TrustDomain.of("example.org"), "workload-1"))
                        .expectedNumberOfCerts(2)
                        .expectedPrivateKeyAlgorithm("EC")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("3. Missing certificate")
                        .certsPath(keyRSA)
                        .keyPath(keyRSA)
                        .expectedError("Certificate could not be parsed from cert bytes")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("4. Missing key")
                        .certsPath(certSingle)
                        .keyPath(certSingle)
                        .expectedError("Private Key could not be parsed from key bytes")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("5. Corrupted private key")
                        .certsPath(certSingle)
                        .keyPath(corrupted)
                        .expectedError("Private Key could not be parsed from key bytes")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("6. Corrupted certificate")
                        .certsPath(corrupted)
                        .keyPath(keyRSA)
                        .expectedError("Certificate could not be parsed from cert bytes")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("7. Certificate does not match private key: RSA keys")
                        .certsPath(certSingle)
                        .keyPath(keyRSAOther)
                        .expectedError("Private Key does not match Certificate Public Key")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("8. Certificate does not match private key: EC keys")
                        .certsPath(certMultiple)
                        .keyPath(keyECDSAOther)
                        .expectedError("Private Key does not match Certificate Public Key")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("9. Certificate without SPIFFE ID")
                        .certsPath(leafEmptyID)
                        .keyPath(keyRSA)
                        .expectedError("Certificate does not contain SPIFFE ID in the URI SAN")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("10. Leaf certificate with CA flag set to true")
                        .certsPath(leafCAtrue)
                        .keyPath(keyRSA)
                        .expectedError("Leaf certificate must not have CA flag set to true")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("11. Leaf certificate without digitalSignature as key usage")
                        .certsPath(leafNoDigitalSignature)
                        .keyPath(keyRSA)
                        .expectedError("Leaf certificate must have 'digitalSignature' as key usage")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("12. Leaf certificate with certSign as key usage")
                        .certsPath(leafCertSign)
                        .keyPath(keyRSA)
                        .expectedError("Leaf certificate must not have 'keyCertSign' as key usage")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("13. Leaf certificate with cRLSign as key usage")
                        .certsPath(leafCRLSign)
                        .keyPath(keyRSA)
                        .expectedError("Leaf certificate must not have 'cRLSign' as key usage")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("14. Signing certificate without CA flag")
                        .certsPath(signNoCA)
                        .keyPath(keyRSA)
                        .expectedError("Signing certificate must have CA flag set to true")
                        .build()
                ),
                Arguments.of(TestCase
                        .builder()
                        .name("15. Signing certificate without CA flag")
                        .certsPath(signNoKeyCertSign)
                        .keyPath(keyRSA)
                        .expectedError("Signing certificate must have 'keyCertSign' as key usage")
                        .build()
                )
        );
    }

    @Test
    void testLoad_Success() throws URISyntaxException {

        Path certPath = Paths.get(toUri(certSingle));
        Path keyPath = Paths.get(toUri(keyRSA));
        try {
            X509Svid x509Svid = X509Svid.load(certPath, keyPath);
            assertEquals("spiffe://example.org/workload-1", x509Svid.getSpiffeId().toString());
        } catch (X509SvidException e) {
            fail(e);
        }
    }

    @Test
    void testLoad_FailsCannotReadCertFile() throws URISyntaxException {
        Path keyPath = Paths.get(toUri(keyRSA));
        try {
            X509Svid.load(Paths.get("not-existent-cert"), keyPath);
            fail("should have thrown IOException");
        } catch (X509SvidException e) {
            assertEquals("Cannot read certificate file", e.getMessage());
        }
    }

    @Test
    void testLoad_FailsCannotReadKeyFile() throws URISyntaxException {
        Path certPath = Paths.get(toUri(certSingle));
        try {
            X509Svid.load(certPath, Paths.get("not-existent-key"));
            fail("should have thrown IOException");
        } catch (X509SvidException e) {
            assertEquals("Cannot read private key file", e.getMessage());
        }
    }

    @Test
    void testLoad_nullCertFilePath_throwsNullPointerException() throws URISyntaxException {
        try {
            X509Svid.load(null, Paths.get(toUri(keyRSA)));
            fail("should have thrown exception");
        } catch (NullPointerException | X509SvidException e) {
            assertEquals("certsFilePath is marked non-null but is null", e.getMessage());
        }
    }

    @Test
    void testLoad_nullKeyFilePath_throwsNullPointerException() throws URISyntaxException, X509SvidException {
        try {
            X509Svid.load(Paths.get(toUri(certSingle)), null);
            fail("should have thrown exception");
        } catch (NullPointerException e) {
            assertEquals("privateKeyFilePath is marked non-null but is null", e.getMessage());
        }
    }

    @Test
    void testParse_nullByteArray_throwsNullPointerException() throws X509SvidException {
        try {
            X509Svid.parse(null, "key".getBytes());
            fail("should have thrown exception");
        } catch (NullPointerException e) {
            assertEquals("certsBytes is marked non-null but is null", e.getMessage());
        }
    }

    @Test
    void testParse_nullKeyByteArray_throwsNullPointerException() throws X509SvidException {
        try {
            X509Svid.parse("cert".getBytes(), null);
            fail("should have thrown exception");
        } catch (NullPointerException e) {
            assertEquals("privateKeyBytes is marked non-null but is null", e.getMessage());
        }
    }

    @Test
    void testGetX509Svid() throws URISyntaxException, X509SvidException {
        Path certPath = Paths.get(toUri(certSingle));
        Path keyPath = Paths.get(toUri(keyRSA));
        X509Svid x509Svid = X509Svid.load(certPath, keyPath);
        assertEquals(x509Svid, x509Svid.getX509Svid());
    }

    @Test
    void testGetChainArray() throws URISyntaxException, X509SvidException {
        Path certPath = Paths.get(toUri(certMultiple));
        Path keyPath = Paths.get(toUri(keyECDSA));
        X509Svid x509Svid = X509Svid.load(certPath, keyPath);
        X509Certificate[] x509CertificatesArray = x509Svid.getChainArray();
        assertEquals(x509Svid.getChain().get(0), x509CertificatesArray[0]);
        assertEquals(x509Svid.getChain().get(1), x509CertificatesArray[1]);
    }

    @ParameterizedTest
    @MethodSource("provideX509SvidScenarios")
    void parseX509Svid(TestCase testCase) {
        try {
            Path certPath = Paths.get(toUri(testCase.certsPath));
            Path keyPath = Paths.get(toUri(testCase.keyPath));
            byte[] certBytes = Files.readAllBytes(certPath);
            byte[] keyBytes = Files.readAllBytes(keyPath);

            X509Svid x509Svid = X509Svid.parse(certBytes, keyBytes);

            if (StringUtils.isNotBlank(testCase.expectedError)) {
                fail(String.format("Error was expected: %s", testCase.expectedError));
            }

            assertNotNull(x509Svid);
            assertNotNull(x509Svid.getSpiffeId());
            assertNotNull(x509Svid.getChain());
            assertNotNull(x509Svid.getPrivateKey());
            assertEquals(testCase.expectedNumberOfCerts, x509Svid.getChain().size());
            assertEquals(testCase.expectedSpiffeId, x509Svid.getSpiffeId());
            assertEquals(testCase.expectedPrivateKeyAlgorithm, x509Svid.getPrivateKey().getAlgorithm());

        } catch (Exception e) {
            if (StringUtils.isBlank(testCase.expectedError)) {
                fail(e);
            }
            assertEquals(testCase.expectedError, e.getMessage());
        }
    }

    private URI toUri(String path) throws URISyntaxException {
        return getClass().getClassLoader().getResource(path).toURI();
    }

    @Value
    static class TestCase {
        String name;
        String certsPath;
        String keyPath;
        SpiffeId expectedSpiffeId;
        int expectedNumberOfCerts;
        String expectedPrivateKeyAlgorithm;
        String expectedError;

        @Builder
        public TestCase(String name, String certsPath, String keyPath, SpiffeId expectedSpiffeId, int expectedNumberOfCerts, String expectedPrivateKeyAlgorithm, String expectedError) {
            this.name = name;
            this.certsPath = certsPath;
            this.keyPath = keyPath;
            this.expectedSpiffeId = expectedSpiffeId;
            this.expectedNumberOfCerts = expectedNumberOfCerts;
            this.expectedPrivateKeyAlgorithm = expectedPrivateKeyAlgorithm;
            this.expectedError = expectedError;
        }
    }
}
