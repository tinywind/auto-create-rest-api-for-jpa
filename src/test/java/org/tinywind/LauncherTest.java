package org.tinywind;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherTest {
    @Test
    void preservesCrudEndpointsWithTheUpdatedPersistenceStack() throws Exception {
        try (ConfigurableApplicationContext context = SpringApplication.run(Launcher.class,
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:launcher-test;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.h2.console.enabled=false")) {
            String url = "http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                    + "/!api/parent";
            assertEquals(200, request("POST", url, "{\"name\":\"security-update\"}").statusCode());
            try (EntityManager manager = context.getBean(EntityManagerFactory.class).createEntityManager()) {
                Long id = manager.createQuery("select p.id from Parent p", Long.class).getSingleResult();
                HttpResponse<String> listed = request("GET", url, null);
                assertEquals(200, listed.statusCode());
                assertTrue(listed.body().contains("security-update"), listed.body());
                assertEquals(200, request("PUT", url + "/" + id, "{\"name\":\"updated\"}").statusCode());
                assertTrue(request("GET", url + "/" + id, null).body().contains("updated"));
                assertEquals(200, request("DELETE", url + "/" + id, null).statusCode());
                assertEquals("[]", request("GET", url, null).body());
            }
        }
    }

    @Test
    void bindsSearchValuesAndRejectsUnknownIdentifiers() throws Exception {
        try (ConfigurableApplicationContext context = SpringApplication.run(Launcher.class,
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:search-test;DB_CLOSE_DELAY=-1",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.h2.console.enabled=false")) {
            String url = "http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                    + "/!api/parent";
            assertEquals(200, request("POST", url, "{\"name\":\"Alice\"}").statusCode());
            assertEquals(200, request("POST", url, "{\"name\":\"Bob\"}").statusCode());
            String attack = java.net.URLEncoder.encode("missing%' OR 1=1 OR name LIKE '%", java.nio.charset.StandardCharsets.UTF_8);
            HttpResponse<String> injected = request("GET", url + "?.search&name=" + attack + "&name.0=LIKE", null);
            assertEquals(200, injected.statusCode());
            assertEquals("[]", injected.body());
            HttpResponse<String> matched = request("GET", url + "?.search&name=Ali&name.0=LIKE", null);
            assertEquals(200, matched.statusCode());
            assertTrue(matched.body().contains("Alice"));
            assertTrue(!matched.body().contains("Bob"));
            assertEquals(200, request("POST", url, "{\"name\":\"O'Reilly\"}").statusCode());
            HttpResponse<String> quoted = request("GET", url + "?.search&name=O%27Reilly", null);
            assertEquals(200, quoted.statusCode());
            assertTrue(quoted.body().contains("O'Reilly"));
            try (EntityManager manager = context.getBean(EntityManagerFactory.class).createEntityManager()) {
                Long id = manager.createQuery("select p.id from Parent p where p.name = :name", Long.class)
                        .setParameter("name", "Alice").getSingleResult();
                for (String operator : new String[]{"%3E%3D", "%3C%3D"}) {
                    HttpResponse<String> numeric = request("GET", url + "?.search&name=Alice&id=" + id + "&id.0=" + operator, null);
                    assertEquals(200, numeric.statusCode());
                    assertTrue(numeric.body().contains("Alice"));
                    assertTrue(!numeric.body().contains("Bob"));
                }
                assertEquals("[]", request("GET", url + "?.search&name=Alice&id=" + id + "&id.0=%3E", null).body());
                assertEquals("[]", request("GET", url + "?.search&name=Alice&id=" + id + "&id.0=%3C", null).body());
            }
            assertEquals(400, request("GET", url + "?.search&id=not-a-number", null).statusCode());
            assertEquals(400, request("GET", url + "?.search&unknown=Alice", null).statusCode());
            assertEquals(400, request("GET", url + "?.search&name=Alice&name.0=INVALID", null).statusCode());
            assertEquals(400, request("GET", url + "%20p%20where%201=1?.search", null).statusCode());
        }
    }

    private HttpResponse<String> request(String method, String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
