package org.eclipse.slm.tests.stack;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.ArrayList;
import java.util.List;

public class TestConfig {

    // Common
    public static final String DOCKER_HOST = System.getenv().getOrDefault("DOCKER_HOST", "tcp://localhost:2375");
    public static final String HOST = System.getenv().getOrDefault("TARGET_HOST", "ipa-wn1152");

    /// Keycloak
    public static final String KEYCLOAK_REALM = System.getenv().getOrDefault("KEYCLOAK_REALM", "fabos");
    public static final String KEYCLOAK_USERNAME = System.getenv().getOrDefault("KEYCLOAK_USERNAME", "fabos");
    public static final String KEYCLOAK_PASSWORD = System.getenv().getOrDefault("KEYCLOAK_PASSWORD", "password");
    public static final int KEYCLOAK_PORT = Integer.parseInt(System.getenv().getOrDefault("KEYCLOAK_PORT", "7080"));
    public static final String KEYCLOAK_BASE_URL =  "http://" + TestConfig.HOST;
    public static final RequestSpecification KEYCLOAK_SERVICE_SPEC = new RequestSpecBuilder()
                .setContentType(ContentType.URLENC)
            .setBaseUri("http://" + TestConfig.HOST)
            .setPort(TestConfig.KEYCLOAK_PORT)
            .setBasePath("/auth/realms/" + KEYCLOAK_REALM)
            .build();

    // Test Resource
    public static final String TEST_RESOURCE_HOSTNAME = System.getenv().getOrDefault("TEST_RESOURCE_HOSTNAME", "10.3.7.168");
    public static final String TEST_RESOURCE_IP = System.getenv().getOrDefault("TEST_RESOURCE_IP", "10.3.7.168");
    public static final String TEST_RESOURCE_USERNAME = System.getenv().getOrDefault("TEST_RESOURCE_USERNAME", "vfk");
    public static final String TEST_RESOURCE_PASSWORD = System.getenv().getOrDefault("TEST_RESOURCE_PASSWORD", "667d0224");

    // Test Resource List
    public static List<TestResource> testResourceList = new ArrayList<>(List.of(
            new TestResource("192.168.0.150", "192.168.0.150", "root", "password"),
            new TestResource("192.168.0.151", "192.168.0.151", "root", "password"),
            new TestResource("192.168.0.152", "192.168.0.152", "root", "password"),
            new TestResource("192.168.0.153", "192.168.0.153", "root", "password"),
            new TestResource("192.168.0.154", "192.168.0.154", "root", "password")
    ));

    // Test Raspi Resource List
    public static List<TestResource> testRaspiResourceList = new ArrayList<>(List.of(
            new TestResource("fabos-edge-pi-01", "192.168.0.151", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-02", "192.168.0.168", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-03", "192.168.0.140", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-04", "192.168.0.143", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-05", "192.168.0.123", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-06", "192.168.0.130", "pi", "fabos_01"),
            new TestResource("fabos-edge-pi-07", "192.168.0.237", "pi", "fabos_01")
    ));

    // AWX
    public static final int AWX_PORT = Integer.parseInt(System.getenv().getOrDefault("AWX_PORT", "80"));
    public static final String AWX_BASE_URL =  "http://" + TestConfig.HOST;

    // Consul
    public static final int CONSUL_PORT = Integer.parseInt(System.getenv().getOrDefault("CONSUL_PORT", "8500"));
    public static final String CONSUL_BASE_URL =  "http://" + TestConfig.HOST;

    // Vault
    public static final int VAULT_PORT = Integer.parseInt(System.getenv().getOrDefault("VAULT_PORT", "8200"));
    public static final String VAULT_BASE_URL =  "http://" + TestConfig.HOST;

    // Notification Service
    public static final int NOTIFICATION_SERVICE_PORT = Integer.parseInt(System.getenv().getOrDefault("NOTIFICATION_SERVICE_PORT", "9001"));
    public static final String NOTIFICATION_SERVICE_BASE_URL =  "http://" + TestConfig.HOST;

    // Resource Registry
    public static final int RESOURCE_REGISTRY_PORT = Integer.parseInt(System.getenv().getOrDefault("RESOURCE_REGISTRY_PORT", "9010"));
    public static final String RESOURCE_REGISTRY_BASE_URL =  "http://" + TestConfig.HOST;

    // Service Registry
    public static final int SERVICE_REGISTRY_PORT = Integer.parseInt(System.getenv().getOrDefault("SERVICE_REGISTRY_PORT", "9020"));
    public static final String SERVICE_REGISTRY_BASE_URL =  "http://" + TestConfig.HOST;

    // Test Service Vendor
    public static final TestServiceVendor TEST_SERVICE_VENDOR =  new TestServiceVendor("fabos");
}
