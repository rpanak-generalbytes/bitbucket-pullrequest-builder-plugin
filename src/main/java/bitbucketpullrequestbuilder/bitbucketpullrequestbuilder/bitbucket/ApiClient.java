package bitbucketpullrequestbuilder.bitbucketpullrequestbuilder.bitbucket;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.*;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.util.EncodingUtil;
import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.codehaus.jackson.type.JavaType;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import hudson.ProxyConfiguration;

/**
 * Created by nishio
 */
public abstract class ApiClient {
    private static final Logger logger = Logger.getLogger(ApiClient.class.getName());

    private static final String COMPUTED_KEY_FORMAT = "%s-%s";
    public static final byte MAX_KEY_SIZE_BB_API = 40;

    protected String owner;
    protected String repositoryName;
    protected Credentials credentials;
    protected String key;
    protected String name;
    protected HttpClientFactory factory;

    private static MessageDigest SHA1 = null;

    public static class HttpClientFactory {
        public static final HttpClientFactory INSTANCE = new HttpClientFactory();
        private static final int DEFAULT_TIMEOUT = 60000;

        public HttpClient getInstanceHttpClient() {
            HttpClient client = new HttpClient();

            HttpClientParams params = client.getParams();
            params.setConnectionManagerTimeout(DEFAULT_TIMEOUT);
            params.setSoTimeout(DEFAULT_TIMEOUT);

            if (Jenkins.getInstance() == null) return client;

            ProxyConfiguration proxy = getInstance().proxy;
            if (proxy == null) return client;

            logger.log(Level.FINE, "Jenkins proxy: {0}:{1}", new Object[]{ proxy.name, proxy.port });
            client.getHostConfiguration().setProxy(proxy.name, proxy.port);
            String username = proxy.getUserName();
            String password = proxy.getPassword();

            // Consider it to be passed if username specified. Sufficient?
            if (username != null && !"".equals(username.trim())) {
                logger.log(Level.FINE, "Using proxy authentication (user={0})", username);
                client.getState().setProxyCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));
            }

            return client;
        }

        private Jenkins getInstance() {
            final Jenkins instance = Jenkins.getInstance();
            if (instance == null){
                throw new IllegalStateException("Jenkins instance is NULL!");
            }
            return instance;
        }
    }

    public <T extends HttpClientFactory> ApiClient(
        String username, String password,
        String owner, String repositoryName,
        String key, String name,
        T httpFactory
    ) {
        this.credentials = new UsernamePasswordCredentials(username, password);
        this.owner = owner;
        this.repositoryName = repositoryName;
        this.key = key;
        this.name = name;
        this.factory = httpFactory != null ? httpFactory : HttpClientFactory.INSTANCE;
    }

    /**
     * Retrun
     * @param keyExPart
     * @return key parameter for call BitBucket API
     */
    protected String computeAPIKey(String keyExPart) {
      String computedKey = String.format(COMPUTED_KEY_FORMAT, this.key, keyExPart);

      if (computedKey.length() > MAX_KEY_SIZE_BB_API) {
        try {
          if (SHA1 == null) SHA1 = MessageDigest.getInstance("SHA1");
          return new String(Hex.encodeHex(SHA1.digest(computedKey.getBytes("UTF-8"))));
        } catch(NoSuchAlgorithmException e) {
          logger.log(Level.WARNING, "Failed to create hash provider", e);
        } catch (UnsupportedEncodingException e) {
          logger.log(Level.WARNING, "Failed to create hash provider", e);
        }
      }
      return (computedKey.length() <= MAX_KEY_SIZE_BB_API) ?  computedKey : computedKey.substring(0, MAX_KEY_SIZE_BB_API);
    }

    private HttpClient getHttpClient() {
        return this.factory.getInstanceHttpClient();
    }

    protected String get(String path) {
        return send(new GetMethod(path));
    }

    protected String post(String path, NameValuePair[] data) {
        PostMethod req = new PostMethod(path);
        req.setRequestBody(data);
        req.getParams().setContentCharset("utf-8");
        return send(req);
    }

    // Public static JSON serializer, so we can test serialization
    public static String serializeObject(Object obj) throws java.io.IOException {
        String jsonStr = new ObjectMapper().
            setSerializationInclusion(Inclusion.NON_NULL).
            writeValueAsString(obj);
        return jsonStr;
    }

    protected String post(String path, Object data) {
        try {
            final String jsonStr = ApiClient.serializeObject(data);
            final StringRequestEntity entity = new StringRequestEntity(jsonStr, "application/json", "utf-8");
            PostMethod req = new PostMethod(path);
            req.setRequestEntity(entity);
            logger.log(Level.FINE, "SENDING:\n" + jsonStr + "\n");
            return send(req);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Not able to parse data to json", e);
        }
        return null;
    }

    protected String post(String path) {
        final PostMethod req = new PostMethod(path);
        req.setRequestHeader("X-Atlassian-Token", "no-check");
        return send(req);
    }

    protected void delete(String path) {
         send(new DeleteMethod(path));
    }

    protected void put(String path, NameValuePair[] data) {
        PutMethod req = new PutMethod(path);
        req.setRequestBody(EncodingUtil.formUrlEncode(data, "utf-8"));
        req.getParams().setContentCharset("utf-8");
        send(req);
    }

    private String send(HttpMethodBase req) {
        HttpClient client = getHttpClient();
        client.getState().setCredentials(AuthScope.ANY, credentials);
        client.getParams().setAuthenticationPreemptive(true);
        try {
            int statusCode = client.executeMethod(req);
            if (statusCode == HttpStatus.SC_NO_CONTENT) {
                // Empty
                return null;

            // Not sure if We should list the success codes, or check for < 200 and > 207 . . .
            // I kind of prefer listing the ones we expect.
            } else if (statusCode == HttpStatus.SC_OK || statusCode == HttpStatus.SC_ACCEPTED ||
                       statusCode == HttpStatus.SC_CREATED) {
                // Success!
                return IOUtils.toString(req.getResponseBodyAsStream());

            } else {
                // Bad response status
                logger.log(Level.WARNING, "Response status: " + req.getStatusLine()+" URI: "+req.getURI());
                logger.log(Level.WARNING, IOUtils.toString(req.getResponseBodyAsStream()));
            }
        } catch (HttpException e) {
            logger.log(Level.WARNING, "Failed to send request.", e);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to send request.", e);
        } finally {
          req.releaseConnection();
        }
        return null;
    }

    protected <R> R parse(String response, Class<R> cls) throws IOException {
        return new ObjectMapper().readValue(response, cls);
    }
    protected <R> R parse(String response, JavaType type) throws IOException {
        return new ObjectMapper().readValue(response, type);
    }
    protected <R> R parse(String response, TypeReference<R> ref) throws IOException {
        return new ObjectMapper().readValue(response, ref);
    }

    public abstract <T extends AbstractPullrequest> List<T> getPullRequests();

    public abstract List<AbstractPullrequest.Comment> getPullRequestComments(String commentOwnerName, String commentRepositoryName, String pullRequestId);

    public String buildStatusKey(String bsKey) {
        return this.computeAPIKey(bsKey);
    }

    public abstract boolean hasBuildStatus(String owner, String repositoryName, String revision, String keyEx);

    public abstract void setBuildStatus(String owner, String repositoryName, String revision, BuildState state, String buildUrl, String comment, String keyEx);

    public abstract void deletePullRequestApproval(String pullRequestId);

    public abstract AbstractPullrequest.Participant postPullRequestApproval(String pullRequestId);

    public abstract AbstractPullrequest.Comment postPullRequestComment(String pullRequestId, String content);

    public String getName() {
        return this.name;
    }
}
