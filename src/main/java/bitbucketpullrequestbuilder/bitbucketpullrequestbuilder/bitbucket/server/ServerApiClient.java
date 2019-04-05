package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.server;

import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.AbstractPullrequest;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.ApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.BuildState;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.cloud.CloudApiClient;
import bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket.cloud.CloudPullrequest;
import org.apache.commons.collections.CollectionUtils;
import org.codehaus.jackson.map.type.TypeFactory;
import org.codehaus.jackson.type.JavaType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerApiClient extends ApiClient {

    private static final Logger logger = Logger.getLogger(CloudApiClient.class.getName());

    private static final String PULL_REQUESTS_URL = "/pull-requests/";

    private final String serverUrl;

    public <T extends HttpClientFactory> ServerApiClient(String serverUrl, String username, String password, String owner, String repositoryName, String key, String name, T httpFactory) {
        super(username, password, owner, repositoryName, key, name, httpFactory);

        this.serverUrl = serverUrl;
    }

    @Override
    public List<ServerPullrequest> getPullRequests() {
        final List<ServerPullrequest> pullrequests = getAllValues(restV1(PULL_REQUESTS_URL), ServerPullrequest.class);
        return addRepositoryUrls(pullrequests);
    }

    private List<ServerPullrequest> addRepositoryUrls(List<ServerPullrequest> pullrequests) {
        final AbstractPullrequest.RepositoryLinks repositoryLinks = getRepositoryLinks();
        if (repositoryLinks != null) {
            for (ServerPullrequest pullrequest : pullrequests) {
                final ServerPullrequest.Repository sourceRepository = pullrequest.getSource().getRepository();
                final AbstractPullrequest.RepositoryLinks links = sourceRepository.getLinks();
                if(links.getClone().isEmpty()) {
                    sourceRepository.setLinks(repositoryLinks);
                }
            }
        }
        return pullrequests;
    }

    public AbstractPullrequest.RepositoryLinks getRepositoryLinks() {
        final String body = get(restV1(""));
        AbstractPullrequest.RepositoryLinks ret = null;
        try {
            final TypeFactory typeFactory = TypeFactory.defaultInstance();
            final ServerPullrequest.Repository repository = parse(
                    body,
                    typeFactory.constructType(ServerPullrequest.Repository.class)
            );
            ret = repository.getLinks();
        } catch (IOException e) {
            logger.log(Level.WARNING, "invalid response.", e);
        }
        return ret;
    }

    @Override
    public List<AbstractPullrequest.Comment> getPullRequestComments(String commentOwnerName, String commentRepositoryName, String pullRequestId) {
        final List<ServerPullrequest.Activity> activities = getAllValues(restV1(PULL_REQUESTS_URL + pullRequestId + "/activities"), ServerPullrequest.Activity.class);
        return activitiesToComments(activities);
    }

    private List<AbstractPullrequest.Comment> activitiesToComments(List<ServerPullrequest.Activity> activities) {
        if (CollectionUtils.isEmpty(activities))
            return Collections.emptyList();

        final List<AbstractPullrequest.Comment> comments = new ArrayList<>();
        for (final ServerPullrequest.Activity activity : activities) {
            if (activity.isComment()) {
                comments.add(activity.toComment());
            }
        }

        return comments;
    }

    @Override
    public boolean hasBuildStatus(String owner, String repositoryName, String revision, String keyEx) {
        return CollectionUtils.isNotEmpty(getAllValues(buildStateV1(revision), ServerPullrequest.CommitBuildState.class));
    }

    @Override
    public void setBuildStatus(String owner, String repositoryName, String revision, BuildState state, String buildUrl, String comment, String keyEx) {
        String url = buildStateV1(revision);
        String computedKey = computeAPIKey(keyEx);
        ServerPullrequest.CommitBuildState commit = new ServerPullrequest.CommitBuildState();

        // FIXME TODO WTF? Where do you put the comment?

        commit.setKey(computedKey);
        commit.setState(state);
        commit.setUrl(buildUrl);

        String resp = post(url, commit);

        logger.log(Level.FINE, "POST state {0} to {1} with key {2} with response {3}", new Object[]{
            state, url, computedKey, resp}
        );
    }

    @Override
    public void deletePullRequestApproval(String pullRequestId) {
        delete(restV1(PULL_REQUESTS_URL + pullRequestId + "/approve"));
    }

    @Override
    public CloudPullrequest.Participant postPullRequestApproval(String pullRequestId) {
        post(restV1(PULL_REQUESTS_URL + pullRequestId + "/approve"));
        return null;
    }

    @Override
    public ServerPullrequest.Comment postPullRequestComment(String pullRequestId, String content) {
        ServerPullrequest.Comment comment = new ServerPullrequest.Comment();
        comment.setContent(content);
        try {
            post(restV1(PULL_REQUESTS_URL + pullRequestId + "/comments"), comment);
        } catch(Exception e) {
            logger.log(Level.WARNING, "Invalid pull request comment response.", e);
        }
        return comment;
    }

    private String restV1(String url) {
        return this.serverUrl + "/rest/api/1.0/projects/" + this.owner + "/repos/" + this.repositoryName + url;
    }

    private String buildStateV1(String commit) {
        return this.serverUrl + "/rest/build-status/1.0/commits/" + commit;
    }

    private <T> List<T> getAllValues(String rootUrl, Class<T> cls) {
        List<T> values = new ArrayList<>();
        try {
            String url = rootUrl;
            do {
                final JavaType type = TypeFactory.defaultInstance().constructParametricType(AbstractPullrequest.Response.class, cls);
                final String respBody = get(url);
                AbstractPullrequest.Response<T> response = parse(respBody, type);
                values.addAll(response.getValues());
                url = response.getNext();
            } while (url != null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "invalid response.", e);
        }
        return values;
    }

}
