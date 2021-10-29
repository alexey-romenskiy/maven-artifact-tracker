package codes.writeonce.maven.artifact.tracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.MutablePlexusContainer;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

@Named
@Singleton
public class ArtifactTrackerEventSpy extends AbstractEventSpy {

    private Logger logger;
    private String buildVcsNumber;
    private String teamcityBuildId;
    private String teamcityBuildConfName;
    private String teamcityProjectName;
    private boolean teamcityPropertiesAvailable;

    @Override
    public void init(Context context) throws Exception {

        final Map<String, Object> data = context.getData();
        final MutablePlexusContainer container = (DefaultPlexusContainer) data.get("plexus");
        logger = requireNonNull(container.getLogger());

        final Object systemProperties = data.get("systemProperties");
        if (systemProperties instanceof Properties) {
            final Properties p = (Properties) systemProperties;
            final String property = p.getProperty("env.TEAMCITY_BUILD_PROPERTIES_FILE");
            if (property != null) {
                final Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(Paths.get(property))) {
                    properties.load(in);
                }
                buildVcsNumber = properties.getProperty("build.vcs.number");
                teamcityBuildId = properties.getProperty("teamcity.build.id");
                teamcityBuildConfName = properties.getProperty("teamcity.buildConfName");
                teamcityProjectName = properties.getProperty("teamcity.projectName");
                teamcityPropertiesAvailable = true;
            }
        }
    }

    @Override
    public void onEvent(Object event) {

        try {
            if (event instanceof RepositoryEvent) {
                final RepositoryEvent re = (RepositoryEvent) event;
                if (re.getType() == RepositoryEvent.EventType.ARTIFACT_DEPLOYED) {
                    final Exception exception = re.getException();
                    if (exception == null) {
                        final Artifact artifact = re.getArtifact();
                        try {
                            final Map<String, Object> body = new HashMap<>();
                            ofNullable(artifact.getGroupId()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("groupId", v));
                            ofNullable(artifact.getArtifactId()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("artifactId", v));
                            ofNullable(artifact.getClassifier()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("classifier", v));
                            ofNullable(artifact.getExtension()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("extension", v));
                            ofNullable(artifact.getBaseVersion()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("baseVersion", v));
                            ofNullable(artifact.getVersion()).map(this::trimToNull)
                                    .ifPresent(v -> body.put("version", v));
                            body.put("snapshot", artifact.isSnapshot());
                            ofNullable(re.getRepository()).map(this::getUrl).map(this::trimToNull)
                                    .ifPresent(v -> body.put("url", v));
                            if (teamcityPropertiesAvailable) {
                                final Map<String, Object> teamcityInfo = new HashMap<>();
                                ofNullable(buildVcsNumber).ifPresent(v -> teamcityInfo.put("buildVcsNumber", v));
                                ofNullable(teamcityBuildId).ifPresent(v -> teamcityInfo.put("teamcityBuildId", v));
                                ofNullable(teamcityBuildConfName).ifPresent(
                                        v -> teamcityInfo.put("teamcityBuildConfName", v));
                                ofNullable(teamcityProjectName).ifPresent(
                                        v -> teamcityInfo.put("teamcityProjectName", v));
                                body.put("teamcity", teamcityInfo);
                            }

                            try (CloseableHttpClient client = HttpClients.createDefault()) {
                                final HttpPost httpPost = new HttpPost("https://node3.trade-mate.io/tracker/api/track");

                                httpPost.addHeader(new BasicScheme().authenticate(
                                        new UsernamePasswordCredentials("alexey.romenskiy@gmail.com", "DW]cidCs!Bq9"),
                                        httpPost, null));

                                httpPost.setEntity(new StringEntity(getBodyAsString(body), UTF_8));
                                httpPost.setHeader("Accept", "application/json");
                                httpPost.setHeader("Content-type", "application/json; charset=utf-8");
                                try (CloseableHttpResponse response = client.execute(httpPost)) {
                                    final int statusCode = response.getStatusLine().getStatusCode();
                                    if (statusCode == 200) {
                                        logger.info("Artifact tracked: " + getArtifactInfo(artifact));
                                    } else {
                                        logger.error("Failed to track artifact: " + getArtifactInfo(artifact) +
                                                     ". Server returned HTTP status code: " + statusCode);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Failed to track artifact: " + getArtifactInfo(artifact), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to track artifacts", e);
        }
    }

    private String getBodyAsString(Map<String, Object> body) throws JsonProcessingException {

        final ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(body);
    }

    private String getArtifactInfo(Artifact artifact) {

        return "[" + artifact.getGroupId() +
               ":" + artifact.getArtifactId() +
               ":" + artifact.getClassifier() +
               ":" + artifact.getExtension() +
               ":" + artifact.getVersion() +
               "]";
    }

    private String getUrl(ArtifactRepository repository) {

        final String url;
        if (repository instanceof RemoteRepository) {
            RemoteRepository remoteRepository = (RemoteRepository) repository;
            url = remoteRepository.getUrl();
        } else {
            url = null;
        }
        return url;
    }

    private String trimToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
