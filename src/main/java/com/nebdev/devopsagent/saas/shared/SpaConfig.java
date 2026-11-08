package com.nebdev.devopsagent.saas.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Application Angular servie sous {@code /app/} (build {@code web/dist/web/browser}, ou {@code AUTOPILOT_WEB_DIR}
 * dans l'image). Une route Angular inconnue renvoie {@code index.html} ; la racine redirige vers l'application
 * (la console locale historique n'a pas de sens en SaaS).
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    private final String webDir;

    public SpaConfig(@Value("${AUTOPILOT_WEB_DIR:web/dist/web/browser}") String webDir) {
        this.webDir = webDir.endsWith("/") ? webDir : webDir + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations("file:" + webDir)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource requested = location.createRelative(path);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Route Angular (pas un fichier) : l'application gère la navigation.
                        return path.contains(".") ? null : new FileSystemResource(webDir + "index.html");
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}
