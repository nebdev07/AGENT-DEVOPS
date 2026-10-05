package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformTemplatesTest {

    @TempDir
    Path tmp;

    private static PlatformSpec arcLike() {
        return PlatformSpec.normalized("demo", "apps.exemple.com", "path", List.of(
                new PlatformServiceSpec("www", "git@github.com:org/demo-site.git", "main", "", 3000, "",
                        List.of(), Map.of("NEXT_PUBLIC_API_URL", "https://{{HOST}}/demo-api"), "/",
                        "next", Map.of(), false),
                new PlatformServiceSpec("api", "git@github.com:org/demo-api.git", "main", "", 8080, "src",
                        List.of("mariadb", "cache", "volume:uploads"), Map.of(), "/demo-api",
                        "spring-boot-11",
                        Map.of("APP_PUBLIC_BASE_URL", "https://{{HOST}}/demo-api", "MAIL_PASSWORD", "s3cr$t"),
                        true)
        ), null);
    }

    @Test
    void hostPlaceholderIsKeptInIntentAndResolvedForTheServer() {
        PlatformSpec p = arcLike();
        assertThat(p.services().get(0).buildArgs().get("NEXT_PUBLIC_API_URL")).isEqualTo("https://{{HOST}}/demo-api");
        PlatformServiceSpec www = p.resolved().services().get(0);
        PlatformServiceSpec api = p.resolved().services().get(1);
        assertThat(www.buildArgs().get("NEXT_PUBLIC_API_URL")).isEqualTo("https://demo.apps.exemple.com/demo-api");
        assertThat(api.env().get("APP_PUBLIC_BASE_URL")).isEqualTo("https://demo.apps.exemple.com/demo-api");
        assertThat(PlatformCompose.yaml(p.resolved()))
                .doesNotContain("{{HOST}}")
                .contains("https://demo.apps.exemple.com/demo-api");
        assertThat(api.needs()).contains(PlatformServiceSpec.NEED_REDIS_PRIVATE);
        assertThat(api.keepPath()).isTrue();
        assertThat(www.keepPath()).isFalse();
    }

    @Test
    void composeKeepsPrefixAddsPrivateCacheAndTemplateDockerfile() {
        PlatformSpec p = arcLike();
        String yaml = PlatformCompose.yaml(p);
        assertThat(yaml).contains("PathPrefix(`/demo-api`)");
        assertThat(yaml).doesNotContain("stripprefix.prefixes=/demo-api");
        assertThat(yaml).contains("dockerfile: Dockerfile.autopilot");
        assertThat(yaml).contains(PlatformCompose.CACHE_SERVICE + ":");
        assertThat(yaml).contains("internal: true");
        assertThat(yaml).contains("./svc-api.local.env");
        assertThat(PlatformCompose.manifest(p)).contains("github.com-org-demo-api");
    }

    @Test
    void templatesRenderBuildArgsPortAndEntrypoint() {
        PlatformSpec p = arcLike();
        String next = PlatformFiles.dockerfile(p.services().get(0));
        assertThat(next).contains("ARG NEXT_PUBLIC_API_URL").contains("ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}");
        assertThat(next).contains("legacy-peer-deps");
        Map<String, String> files = PlatformFiles.contextFiles(p.services().get(1));
        assertThat(files).containsKeys(PlatformCompose.TEMPLATE_DOCKERFILE, PlatformFiles.ENTRYPOINT);
        assertThat(files.get(PlatformCompose.TEMPLATE_DOCKERFILE)).contains("11").contains("8080").doesNotContain("#JDK#");
        assertThat(PlatformFiles.bundle(p)).contains("api\tsrc/Dockerfile.autopilot\t");
    }

    @Test
    void staticTemplateServesSpaUnderKeptPrefix() {
        PlatformSpec p = PlatformSpec.normalized("app", "", "path", List.of(
                new PlatformServiceSpec("web", "https://github.com/org/web.git", "main", "203.0.113.9", 80, "",
                        List.of(), Map.of("STATIC_BUILD", "npx ng build"), "/app", "static", Map.of(), true)), null);
        PlatformServiceSpec web = p.services().get(0);
        Map<String, String> files = PlatformFiles.contextFiles(web);
        assertThat(files).containsKeys(PlatformCompose.TEMPLATE_DOCKERFILE, PlatformFiles.NGINX_CONF);
        assertThat(files.get(PlatformCompose.TEMPLATE_DOCKERFILE))
                .contains("ARG STATIC_BUILD\n").contains("/usr/share/nginx/html/app").contains("EXPOSE 80")
                .doesNotContain("#WEBROOT#");
        assertThat(files.get(PlatformFiles.NGINX_CONF))
                .contains("location = /app {").contains("try_files $uri $uri/ /app/index.html;")
                .contains("listen 80;");
    }

    @Test
    void envFileQuotesValuesSoComposeDoesNotInterpolate() {
        String env = PlatformFiles.envFile(arcLike().services().get(1));
        assertThat(env).contains("NODE_ENV=production");
        assertThat(env).contains("MAIL_PASSWORD='s3cr$t'");
        assertThat(PlatformFiles.quote("it's $x")).isEqualTo("\"it's $$x\"");
    }

    @Test
    void unknownTemplateAndReservedCacheIdAreRejected() {
        assertThatThrownBy(() -> PlatformSpec.normalized("demo", "apps.exemple.com", "host", List.of(
                new PlatformServiceSpec("api", "https://github.com/org/a.git", "main", "", 8080, "",
                        List.of(), Map.of(), "", "php", Map.of(), false)), null))
                .hasMessageContaining("inconnu");
        assertThatThrownBy(() -> PlatformSpec.normalized("demo", "apps.exemple.com", "host", List.of(
                new PlatformServiceSpec(PlatformCompose.CACHE_SERVICE, "https://github.com/org/a.git", "main", "", 8080, "",
                        List.of(), Map.of(), "")), null))
                .hasMessageContaining("réservé");
    }

    @Test
    void apiMasksSecretsAndSaveKeepsStoredValueBehindMask() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        ObjectMapper mapper = new ObjectMapper();
        PlatformIntentStore store = new PlatformIntentStore(props, mapper, new AppIntentStore(props, mapper));
        store.save(List.of(arcLike()), null);

        PlatformSpec masked = store.load().get(0).masked();
        PlatformServiceSpec api = masked.services().get(1);
        assertThat(api.env().get("MAIL_PASSWORD")).isEqualTo(PlatformServiceSpec.SECRET_MASK);
        assertThat(api.env().get("APP_PUBLIC_BASE_URL")).startsWith("https://");

        store.save(List.of(masked), null);
        assertThat(store.load().get(0).services().get(1).env().get("MAIL_PASSWORD")).isEqualTo("s3cr$t");
    }

    @Test
    void deployKeyIdsAndPagesArePerRepo() {
        assertThat(GitDeployKeyStore.keyId("git@github.com:organisation/demo-api.git"))
                .isEqualTo("github.com-organisation-demo-api");
        assertThat(GitDeployKeyStore.keyId("https://github.com/org/public.git")).isEmpty();
        assertThat(GitDeployKeyStore.deployKeyPage("git@github.com:organisation/demo-site.git"))
                .isEqualTo("https://github.com/organisation/demo-site/settings/keys/new");
    }
}
