package io.spiffe.svid.jwtsvid;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import io.spiffe.bundle.jwtbundle.JwtBundle;
import io.spiffe.exception.JwtSvidException;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.spiffeid.TrustDomain;
import io.spiffe.utils.TestUtils;
import lombok.Builder;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.KeyPair;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtSvidParseInsecureTest {

    @ParameterizedTest
    @MethodSource("provideJwtScenarios")
    void parseJwt(TestCase testCase) {

        try {
            String token = testCase.generateToken.get();
            JwtSvid jwtSvid = JwtSvid.parseInsecure(token, testCase.audience);

            assertEquals(testCase.expectedJwtSvid.getSpiffeId(), jwtSvid.getSpiffeId());
            assertEquals(testCase.expectedJwtSvid.getAudience(), jwtSvid.getAudience());
            assertEquals(testCase.expectedJwtSvid.getExpiry().toInstant().getEpochSecond(), jwtSvid.getExpiry().toInstant().getEpochSecond());
            assertEquals(token, jwtSvid.getToken());
        } catch (Exception e) {
            assertEquals(testCase.expectedException.getClass(), e.getClass());
            assertEquals(testCase.expectedException.getMessage(), e.getMessage());
        }

    }

    @Test
    void testParseInsecure_nullToken_throwsNullPointerException() throws JwtSvidException {
        Set<String> audience = Collections.singleton("audience");

        try {
            JwtSvid.parseInsecure(null, audience);
        } catch (NullPointerException e) {
            assertEquals("token is marked non-null but is null", e.getMessage());
        }
    }

    @Test
    void testParseAndValidate_emptyToken_throwsIllegalArgumentException() throws JwtSvidException {
        Set<String> audience = Collections.singleton("audience");
        try {
            JwtSvid.parseInsecure("", audience);
        } catch (IllegalArgumentException e) {
            assertEquals("Token cannot be blank", e.getMessage());
        }
    }

    @Test
    void testParseInsecure_nullAudience_throwsNullPointerException() throws JwtSvidException {
        try {
            KeyPair key1 = TestUtils.generateECKeyPair(Curve.P_521);
            TrustDomain trustDomain = TrustDomain.of("test.domain");
            SpiffeId spiffeId = trustDomain.newSpiffeId("host");
            Set<String> audience = Collections.singleton("audience");
            Date expiration = new Date(System.currentTimeMillis() + 3600000);
            JWTClaimsSet claims = TestUtils.buildJWTClaimSet(audience, spiffeId.toString(), expiration);

            JwtSvid.parseInsecure(TestUtils.generateToken(claims, key1, "authority1"), null);

        } catch (NullPointerException e) {
            assertEquals("audience is marked non-null but is null", e.getMessage());
        }
    }

    static Stream<Arguments> provideJwtScenarios() {
        KeyPair key1 = TestUtils.generateECKeyPair(Curve.P_521);
        KeyPair key2 = TestUtils.generateECKeyPair(Curve.P_521);

        TrustDomain trustDomain = TrustDomain.of("test.domain");
        JwtBundle jwtBundle = new JwtBundle(trustDomain);
        jwtBundle.putJwtAuthority("authority1", key1.getPublic());
        jwtBundle.putJwtAuthority("authority2", key2.getPublic());

        SpiffeId spiffeId = trustDomain.newSpiffeId("host");
        Date expiration = new Date(System.currentTimeMillis() + 3600000);
        Set<String> audience = Collections.singleton("audience");

        JWTClaimsSet claims = TestUtils.buildJWTClaimSet(audience, spiffeId.toString(), expiration);

        return Stream.of(
                Arguments.of(TestCase.builder()
                        .name("success")
                        .expectedAudience(audience)
                        .generateToken(() -> TestUtils.generateToken(claims, key1, "authority1"))
                        .expectedException(null)
                        .expectedJwtSvid(new JwtSvid(
                                trustDomain.newSpiffeId("host"),
                                audience,
                                expiration,
                                claims.getClaims(), TestUtils.generateToken(claims, key1, "authority1")))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("malformed")
                        .expectedAudience(audience)
                        .generateToken(() -> "invalid token")
                        .expectedException(new IllegalArgumentException("Unable to parse JWT token"))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("missing subject")
                        .expectedAudience(audience)
                        .generateToken(() -> TestUtils.generateToken(TestUtils.buildJWTClaimSet(audience, "", expiration), key1, "authority1"))
                        .expectedException(new JwtSvidException("Token missing subject claim"))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("missing expiration")
                        .expectedAudience(audience)
                        .generateToken(() -> TestUtils.generateToken(TestUtils.buildJWTClaimSet(audience, spiffeId.toString(), null), key1, "authority1"))
                        .expectedException(new JwtSvidException("Token missing expiration claim"))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("token has expired")
                        .expectedAudience(audience)
                        .generateToken(() -> TestUtils.generateToken(TestUtils.buildJWTClaimSet(audience, spiffeId.toString(), new Date()), key1, "authority1"))
                        .expectedException(new JwtSvidException("Token has expired"))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("unexpected audience")
                        .expectedAudience(Collections.singleton("another"))
                        .generateToken(() -> TestUtils.generateToken(claims, key1, "authority1"))
                        .expectedException(new JwtSvidException("expected audience in [another] (audience=[audience])"))
                        .build()),
                Arguments.of(TestCase.builder()
                        .name("invalid subject claim")
                        .expectedAudience(audience)
                        .generateToken(() -> TestUtils.generateToken(TestUtils.buildJWTClaimSet(audience, "non-spiffe-subject", expiration), key1, "authority1"))
                        .expectedException(new JwtSvidException("Subject non-spiffe-subject cannot be parsed as a SPIFFE ID"))
                        .build())
        );
    }

    @Value
    static class TestCase {
        String name;
        Set<String> audience;
        Supplier<String> generateToken;
        Exception expectedException;
        JwtSvid expectedJwtSvid;

        @Builder
        public TestCase(String name, Set<String> expectedAudience, Supplier<String> generateToken,
                        Exception expectedException, JwtSvid expectedJwtSvid) {
            this.name = name;
            this.audience = expectedAudience;
            this.generateToken = generateToken;
            this.expectedException = expectedException;
            this.expectedJwtSvid = expectedJwtSvid;
        }
    }
}