package io.spiffe.provider;

import io.spiffe.bundle.BundleSource;
import io.spiffe.bundle.x509bundle.X509Bundle;
import io.spiffe.exception.BundleNotFoundException;
import io.spiffe.internal.CertificateUtils;
import io.spiffe.spiffeid.SpiffeId;
import io.spiffe.svid.x509svid.X509SvidValidator;
import lombok.NonNull;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Implementation of an X.509 TrustManager for the SPIFFE Provider.
 * <p>
 * Provides methods to validate X.509 certificates chains using trusted certs provided by a {@link BundleSource}
 * maintained via the Workload API and to verify the SPIFFE IDs against a Set of accepted SPIFFE IDs
 * provided by a Supplier.
 */
public final class SpiffeTrustManager extends X509ExtendedTrustManager {

    private static final SpiffeIdVerifier ALLOW_ANY_SPIFFE_ID_VERIFIER = (spiffeId, verifiedChain) -> {};

    private final BundleSource<X509Bundle> x509BundleSource;
    private final SpiffeIdVerifier spiffeIdVerifier;

    /**
     * Constructor.
     * <p>
     * Creates a {@link SpiffeTrustManager} with an X.509 bundle source used to provide the trusted bundles,
     * and a {@link Supplier} of a Set of accepted {@link SpiffeId} to be used during peer SVID validation.
     *
     * @param x509BundleSource          an implementation of a {@link BundleSource}
     * @param acceptedSpiffeIdsSupplier a {@link Supplier} of a Set of accepted SPIFFE IDs.
     */
    public SpiffeTrustManager(@NonNull final BundleSource<X509Bundle> x509BundleSource,
                              @NonNull final Supplier<Set<SpiffeId>> acceptedSpiffeIdsSupplier) {
        this.x509BundleSource = x509BundleSource;
        this.spiffeIdVerifier = new AllowedIdSupplierSpiffeIdVerifier(acceptedSpiffeIdsSupplier);
    }

    /**
     * Constructor.
     * <p>
     * Creates a {@link SpiffeTrustManager} with an X.509 bundle source used to provide the trusted bundles,
     * and a {@link SpiffeIdVerifier} which will be called to determine if a {@link SpiffeId} should be accepted
     * during peer SVID validation.
     *
     * @param x509BundleSource          an implementation of a {@link BundleSource}
     * @param spiffeIdVerifier          a {@link SpiffeIdVerifier} that will be called to determine if a peer's SPIFFE ID is acceptable
     */
    public SpiffeTrustManager(@NonNull final BundleSource<X509Bundle> x509BundleSource,
                              @NonNull final SpiffeIdVerifier spiffeIdVerifier) {
        this.x509BundleSource = x509BundleSource;
        this.spiffeIdVerifier = spiffeIdVerifier;
    }

    /**
     * Constructor.
     * <p>
     * Creates a {@link SpiffeTrustManager} with an X.509 bundle source used to provide the trusted bundles,
     * and a flag to indicate that any SPIFFE ID will be accepted.
     * <p>
     * Any SPIFFE ID will be accepted during peer SVID validation.
     *
     * @param x509BundleSource  an implementation of a {@link BundleSource}
     */
    public SpiffeTrustManager(@NonNull final BundleSource<X509Bundle> x509BundleSource) {
        this.x509BundleSource = x509BundleSource;
        this.spiffeIdVerifier = ALLOW_ANY_SPIFFE_ID_VERIFIER;
    }

    /**
     * Given the partial or complete certificate chain provided by the peer,
     * build a certificate path to a trusted root and return if it can be validated
     * and is trusted for Client SSL authentication based on the authentication type.
     * <p>
     * Throws a {@link CertificateException} if the chain cannot be chained to a trusted bundled,
     * or if the SPIFFE ID in the chain is not in the Set of accepted SPIFFE IDs.
     *
     * @param chain    the peer certificate chain
     * @param authType not used
     * @throws CertificateException when the chain or the SPIFFE ID presented are not trusted.
     */
    @Override
    public void checkClientTrusted(@NonNull final X509Certificate[] chain, final String authType)
            throws CertificateException {
        validatePeerChain(chain);
    }

    /**
     * Given the partial or complete certificate chain provided by the peer,
     * build a certificate path to a trusted root and return if it can be validated
     * and is trusted for Server SSL authentication based on the authentication type.
     * <p>
     * Throws a {@link CertificateException} if the chain cannot be chained to a trusted bundled,
     * or if the SPIFFE ID in the chain is not in the Set of accepted SPIFFE IDs.
     *
     * @param chain    the peer certificate chain
     * @param authType not used
     * @throws CertificateException when the chain or the SPIFFE ID presented are not trusted.
     */
    @Override
    public void checkServerTrusted(@NonNull final X509Certificate[] chain, final String authType)
            throws CertificateException {
        validatePeerChain(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /**
     * {@link #checkClientTrusted(X509Certificate[], String)}
     */
    @Override
    public void checkClientTrusted(@NonNull final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    /**
     * {@link #checkServerTrusted(X509Certificate[], String)}
     */
    @Override
    public void checkServerTrusted(@NonNull final X509Certificate[] chain, final String authType, final Socket socket)
            throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    /**
     * {@link #checkClientTrusted(X509Certificate[], String)}
     */
    @Override
    public void checkClientTrusted(@NonNull final X509Certificate[] chain, final String authType, final SSLEngine sslEngine)
            throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    /**
     * {@link #checkServerTrusted(X509Certificate[], String)}
     */
    @Override
    public void checkServerTrusted(@NonNull final X509Certificate[] chain, final String authType, final SSLEngine sslEngine)
            throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    // Check that the SPIFFE ID in the peer's certificate is accepted and the chain can be validated with a
    // root CA in the bundle source
    private void validatePeerChain(final X509Certificate... chain) throws CertificateException {
        SpiffeId spiffeId = CertificateUtils.getSpiffeId(chain[0]);
        try {
            spiffeIdVerifier.verify(spiffeId, chain);
        } catch (SpiffeVerificationException e) {
            throw new CertificateException(e.getMessage(), e);
        }

        try {
            X509SvidValidator.verifyChain(Arrays.asList(chain), x509BundleSource);
        } catch (BundleNotFoundException e) {
            throw new CertificateException(e.getMessage(), e);
        }
    }
}
