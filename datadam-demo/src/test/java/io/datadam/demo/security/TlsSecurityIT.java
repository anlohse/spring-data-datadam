package io.datadam.demo.security;

import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

@Timeout(90)
class TlsSecurityIT {
    enum CertificateCase { TRUSTED, UNKNOWN_CA, WRONG_HOST, EXPIRED }
    @TempDir static Path root;
    static final String PASSWORD = "test-store-password";
    static final String KEYTOOL = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    static String clientClasspath;
    @BeforeAll static void certificates() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=Ephemeral Datadam Test CA", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "7", "-ext", "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign", "-keystore", file("ca.p12"));
        keytool("-exportcert", "-alias", "ca", "-keystore", file("ca.p12"), "-rfc", "-file", file("ca.pem"));
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", file("ca.pem"), "-keystore", file("trust.p12"));
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-keystore", file("server.p12"));
        keytool("-certreq", "-alias", "server", "-keystore", file("server.p12"), "-file", file("server.csr"));
        keytool("-genkeypair", "-alias", "other", "-dname", "CN=Unrelated Test CA", "-keyalg", "RSA", "-keysize", "2048",
            "-ext", "BC=ca:true", "-keystore", file("other.p12"));
        keytool("-exportcert", "-alias", "other", "-keystore", file("other.p12"), "-rfc", "-file", file("other.pem"));
        keytool("-importcert", "-noprompt", "-alias", "other", "-file", file("other.pem"), "-keystore", file("untrusted.p12"));
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(root.resolve("server.p12"))) { store.load(input, PASSWORD.toCharArray()); }
        String key = pem("PRIVATE KEY", store.getKey("server", PASSWORD.toCharArray()).getEncoded());
        for (String variant : List.of("valid", "wrong", "expired")) {
            var folder = Files.createDirectories(root.resolve(variant));
            var options = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", file("ca.p12"), "-infile", file("server.csr"),
                "-outfile", folder.resolve("server.pem").toString(), "-rfc", "-ext", "BC=ca:false", "-ext", "EKU=serverAuth",
                "-ext", "SAN=" + (variant.equals("wrong") ? "dns:wrong.invalid" : "dns:localhost,ip:127.0.0.1")));
            if (variant.equals("expired")) Collections.addAll(options, "-startdate", "-3d", "-validity", "1");
            else Collections.addAll(options, "-validity", "3");
            keytool(options.toArray(String[]::new)); Files.writeString(folder.resolve("server.key"), key);
        }
        prepareClientBundle();
    }
    @ParameterizedTest @EnumSource(CertificateCase.class)
    void actualTlsConnectionChecksTrustValidityAndEndpointIdentity(CertificateCase scenario) throws Exception {
        String variant = scenario == CertificateCase.WRONG_HOST ? "wrong" : scenario == CertificateCase.EXPIRED ? "expired" : "valid";
        String configuration = "protected-mode yes\ntls-cert-file /tmp/security/server.pem\ntls-key-file /tmp/security/server.key\n"
            + "tls-auth-clients no\ntls-protocols TLSv1.2,TLSv1.3\nuser default off\nuser client on >test-client-password +ping\n";
        try (var server = new DisposableDatadam(root.resolve(variant), configuration, true)) {
            boolean untrusted = scenario == CertificateCase.UNKNOWN_CA;
            String trust = "/tmp/client/" + (untrusted ? "untrusted.p12" : "trust.p12");
            String client = "datadam-tls-client-it-" + UUID.randomUUID();
            try {
                DisposableDatadam.docker("create", "--name", client, "--network", "container:" + server.name, "--entrypoint", "java",
                    io.datadam.demo.support.TestImages.tlsJava(),
                    "-Djavax.net.ssl.trustStore=" + trust, "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStorePassword=" + PASSWORD,
                    "-cp", clientClasspath, TlsConnectionProbe.class.getName(), "6379", scenario == CertificateCase.TRUSTED ? "accept" : "reject", trust);
                DisposableDatadam.docker("cp", root.resolve("client").toAbsolutePath().toString(), client + ":/tmp/client");
                String result = DisposableDatadam.docker("start", "-a", client);
                assertThat(result).contains(scenario == CertificateCase.TRUSTED ? "TLS_ACCEPTED" : "TLS_REJECTED_HANDSHAKE");
            } finally { DisposableDatadam.docker("rm", "-f", "-v", client); }
        }
    }
    static void prepareClientBundle() throws Exception {
        var prepared = io.datadam.demo.support.ContainerJavaClasspath.prepare(root.resolve("client"));
        Path bundle = prepared.directory();
        Files.copy(root.resolve("trust.p12"), bundle.resolve("trust.p12"));
        Files.copy(root.resolve("untrusted.p12"), bundle.resolve("untrusted.p12"));
        clientClasspath = prepared.classpath();
    }
    static String file(String name) { return root.resolve(name).toString(); }
    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der) + "\n-----END " + type + "-----\n";
    }
    static void keytool(String... args) throws Exception {
        var arguments = new ArrayList<>(List.of(args)); Collections.addAll(arguments, "-storepass", PASSWORD, "-storetype", "PKCS12");
        DisposableDatadam.run(Duration.ofSeconds(20), KEYTOOL, arguments.toArray(String[]::new));
    }
}
