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

    private HttpResponse<String> request(String method, String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
