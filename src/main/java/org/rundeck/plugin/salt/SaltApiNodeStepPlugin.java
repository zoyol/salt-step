/**
 * Copyright (c) 2013, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *    following disclaimer.
 *
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 *    the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or
 *    promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.rundeck.plugin.salt;

import static org.rundeck.plugin.salt.validation.Validators.checkNotEmpty;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.NotThreadSafe;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.rundeck.plugin.salt.output.SaltApiResponseOutput;
import org.rundeck.plugin.salt.output.SaltReturnHandler;
import org.rundeck.plugin.salt.output.SaltReturnHandlerRegistry;
import org.rundeck.plugin.salt.output.SaltReturnResponse;
import org.rundeck.plugin.salt.output.SaltReturnResponseParseException;
import org.rundeck.plugin.salt.util.ArgumentParser;
import org.rundeck.plugin.salt.util.DependencyInjectionUtil;
import org.rundeck.plugin.salt.util.ExponentialBackoffTimer;
import org.rundeck.plugin.salt.util.HttpFactory;
import org.rundeck.plugin.salt.util.LogWrapper;
import org.rundeck.plugin.salt.util.RetryingHttpClientExecutor;
import org.rundeck.plugin.salt.validation.SaltStepValidationException;
import org.rundeck.plugin.salt.version.SaltApiCapability;
import org.rundeck.plugin.salt.version.SaltApiVersionCapabilityRegistry;
import org.rundeck.plugin.salt.version.SaltInteractionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.PluginLogger;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.TextArea;
import com.dtolabs.rundeck.plugins.step.NodeStepPlugin;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * This plugin allows salt execution on a specific minion using the salt-api
 * interface.
 *
 * Pre-requisites:
 * <ul>
 * <li>Salt-api must be installed.</li>
 * <li>Project node resources must be configured with the name as the salt minion's name as configured on the salt
 * master.</li>
 * <li>SALT_USER and SALT_PASSWORD options must be configured and provided on the job.</li>
 * </ul>
 */
@NotThreadSafe
@Plugin(name = SaltApiNodeStepPlugin.SERVICE_PROVIDER_NAME, service = ServiceNameConstants.WorkflowNodeStep)
@PluginDescription(title = "Remote Salt Execution", description = "Run a command on a remote salt master through salt-api.")
public class SaltApiNodeStepPlugin implements NodeStepPlugin {
    public enum SaltApiNodeStepFailureReason implements FailureReason {
        EXIT_CODE, ARGUMENTS_MISSING, ARGUMENTS_INVALID, AUTHENTICATION_FAILURE, COMMUNICATION_FAILURE, SALT_API_FAILURE, SALT_TARGET_MISMATCH, INTERRUPTED;
    }

    public static final String SERVICE_PROVIDER_NAME = "salt-api-exec";

    protected static final String SECURE_OPTION_VALUE = "****";

    protected static final String LOGIN_RESOURCE = "/login";
    protected static final String MINION_RESOURCE = "/minions";
    protected static final String JOBS_RESOURCE = "/jobs";
    protected static final String LOGOUT_RESOURCE = "/logout";
    protected static final String SALT_AUTH_TOKEN_HEADER = "X-Auth-Token";
    protected static final String CHAR_SET_ENCODING = "UTF-8";
    protected static final String REQUEST_CONTENT_TYPE = "application/x-www-form-urlencoded";
    protected static final String REQUEST_ACCEPT_HEADER_NAME = "Accept";
    protected static final String JSON_RESPONSE_ACCEPT_TYPE = "application/json";
    protected static final String YAML_RESPONSE_ACCEPT_TYPE = "application/x-yaml";

    protected static final String SALT_OUTPUT_RETURN_KEY = "return";
    protected static final Type JOB_RESPONSE_TYPE = new TypeToken<Map<String, List<Object>>>() {}.getType();

    // -- Parameter names for REST calls to salt-api --
    protected static final String SALT_API_FUNCTION_PARAM_NAME = "fun";
    protected static final String SALT_API_ARGUMENTS_PARAM_NAME = "arg";
    protected static final String SALT_API_TARGET_PARAM_NAME = "tgt";
    protected static final String SALT_API_USERNAME_PARAM_NAME = "username";
    protected static final String SALT_API_PASSWORD_PARAM_NAME = "password";
    protected static final String SALT_API_EAUTH_PARAM_NAME = "eauth";

    // -- Option names expected to be passed in from rundeck --
    protected static final String RUNDECK_DATA_CONTEXT_OPTION_KEY = "option";
    protected static final String RUNDECK_SECURE_DATA_CONTEXT_OPTION_KEY = "secureOption";
    protected static final String SALT_API_END_POINT_OPTION_NAME = "SALT_API_END_POINT";
    protected static final String SALT_API_VERSION_OPTION_NAME = "SALT_API_VERSION";
    protected static final String SALT_API_FUNCTION_OPTION_NAME = "Function";
    protected static final String SALT_API_TARGET_OPTION_NAME = "Target";
    protected static final String SALT_API_EAUTH_OPTION_NAME = "SALT_API_EAUTH";
    protected static final String SALT_USER_OPTION_NAME = "SALT_USER";
    protected static final String SALT_PASSWORD_OPTION_NAME = "SALT_PASSWORD";

    protected static final String SALT_API_END_POINT_EXPRESSION = "${option.SALT_API_END_POINT}";
    protected static final String SALT_API_EAUTH_EXPRESSION = "${option.SALT_API_EAUTH}";

    @PluginProperty(title = SALT_API_END_POINT_OPTION_NAME, description = "Salt Api end point", required = true, defaultValue = SALT_API_END_POINT_EXPRESSION)
    protected String saltEndpoint;

    @PluginProperty(title = SALT_API_VERSION_OPTION_NAME, description = "Salt Api version", required = false)
    protected String saltApiVersion;

    @TextArea
    @PluginProperty(title = SALT_API_FUNCTION_OPTION_NAME, description = "Function (including args) to invoke on salt minions", required = true)
    protected String function;

    @TextArea
    @PluginProperty(title = SALT_API_TARGET_OPTION_NAME, description = "Specifies target minions", required = false)
    protected String target;

    @PluginProperty(title = SALT_API_EAUTH_OPTION_NAME, description = "Salt Master's external authentication system", required = true, defaultValue = SALT_API_EAUTH_EXPRESSION)
    protected String eAuth;

    protected LogWrapper logWrapper;

    @Autowired
    protected SaltApiVersionCapabilityRegistry capabilityRegistry;

    @Autowired
    protected SaltReturnHandler defaultReturnHandler;

    @Autowired
    protected HttpFactory httpFactory;

    @Autowired
    protected SaltReturnHandlerRegistry returnHandlerRegistry;

    @Autowired
    protected RetryingHttpClientExecutor retryExecutor;

    // Maximum delay in ms for polling salt minion response
    @Autowired
    @Value("${saltJobPolling.maximumRetryDelay}")
    protected long maximumRetryDelay;

    // Delay step in ms for polling salt minion response
    @Autowired
    @Value("${saltJobPolling.delayStep}")
    protected long delayStep;

    // Default number of retries for all http requests
    @Autowired
    @Value("${saltApi.http.numRetries}")
    protected int numRetries;

    // Supported API protocols
    protected String[] endPointSchemes;

    @Autowired
    protected ExponentialBackoffTimer.Factory timerFactory;

    public SaltApiNodeStepPlugin() {
        new DependencyInjectionUtil().inject(this);
    }

    @Autowired
    public void setEndPointSchemes(@Value("${saltApi.endPointSchemes}") String epSchemes) throws IllegalArgumentException {
        endPointSchemes = epSchemes.split(",");
    }

    @Override
    public void executeNodeStep(PluginStepContext context, Map<String, Object> configuration, INodeEntry entry)
            throws NodeStepException {
        // Initialize logger for all actions
        setLogWrapper(context.getLogger());
        target = (target == null) ? entry.getNodename() : target;
        logWrapper.debug("Target is %s", target);

        // Extract options from context.
        Map<String, String> optionData = context.getDataContext().get(RUNDECK_DATA_CONTEXT_OPTION_KEY);
        if (optionData == null) {
            throw new NodeStepException("Missing data context.", SaltApiNodeStepFailureReason.ARGUMENTS_MISSING,
                    target);
        }
        String user = optionData.get(SALT_USER_OPTION_NAME);
        String password = optionData.get(SALT_PASSWORD_OPTION_NAME);

        // Extract project globals from context
        Map<String, String> projectData = context.getDataContext().get("globals");

        if (projectData != null) {
            if (saltEndpoint == null || saltEndpoint.equals(SALT_API_END_POINT_EXPRESSION)) {
                String defaultValue = projectData.get(SALT_API_END_POINT_OPTION_NAME);
                if (defaultValue != null)
                    saltEndpoint = defaultValue;
            }

                logWrapper.info("eAuth=" + eAuth + "|" + SALT_API_EAUTH_EXPRESSION);
            if (eAuth == null || eAuth.equals(SALT_API_EAUTH_EXPRESSION)) {
                String defaultValue = projectData.get(SALT_API_EAUTH_OPTION_NAME);
                if (defaultValue != null)
                    eAuth = defaultValue;
            }

            if (user == null) {
                String defaultValue = projectData.get(SALT_USER_OPTION_NAME);
                if (defaultValue != null)
                    user = defaultValue;
            }

            if (password == null) {
                String defaultValue = projectData.get(SALT_PASSWORD_OPTION_NAME);
                if (defaultValue != null)
                    password = defaultValue;
            }
        }

        logWrapper.debug("endpoint=%s\neauth=%s\nuser=%s\npassword=(%s)", saltEndpoint, eAuth, user, password == null ? "no" : "yes");

        validate(user, password, entry);

        try {
            SaltApiCapability capability = getSaltApiCapability();
            logWrapper.debug("Using salt-api version: [%s]", capability);

            HttpClient client = httpFactory.createHttpClient();
            String authToken = authenticate(capability, client, user, password);

            if (authToken == null) {
                throw new NodeStepException("Authentication failure",
                        SaltApiNodeStepFailureReason.AUTHENTICATION_FAILURE, target);
            }


            Set<String> secureData = extractSecureDataFromDataContext(context.getDataContext());
            String dispatchedJid = submitJob(capability, client, authToken, target, secureData);
            logWrapper.info("Received jid [%s] for submitted job", dispatchedJid);
            String jobOutput = waitForJidResponse(client, authToken, dispatchedJid, target);
            SaltReturnHandler handler = returnHandlerRegistry.getHandlerFor(function.split(" ", 2)[0],
                    defaultReturnHandler);
            logWrapper.debug("Using [%s] as salt's response handler for the following raw response", handler);
            SaltReturnResponse response = handler.extractResponse(jobOutput);

            for (String out : response.getStandardOutput()) {
                for (String outLine : out.split("\\\\n")) {
                	logWrapper.info(outLine);
		}
            }
            for (String err : response.getStandardError()) {
                logWrapper.error(err);
            }
            if (!response.isSuccessful()) {
                throw new NodeStepException(String.format("Execution failed on minion with exit code %d",
                        response.getExitCode()), SaltApiNodeStepFailureReason.EXIT_CODE, target);
            }

            if (capability.getSupportsLogout()) {
                logoutQuietly(client, authToken);
            }
        } catch (SaltReturnResponseParseException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.SALT_API_FAILURE, target);
        } catch (InterruptedException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.INTERRUPTED, target);
        } catch (SaltTargettingMismatchException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.SALT_TARGET_MISMATCH, target);
        } catch (SaltApiException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.SALT_API_FAILURE, target);
        } catch (HttpException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.COMMUNICATION_FAILURE, target);
        } catch (IOException e) {
            throw new NodeStepException(e, SaltApiNodeStepFailureReason.COMMUNICATION_FAILURE, target);
        }
    }

    /**
     * @return collection of secure data values from data context.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected Set<String> extractSecureDataFromDataContext(Map dataContext) {
        Map<String, String> secureContext = (Map<String, String>) dataContext.get(RUNDECK_SECURE_DATA_CONTEXT_OPTION_KEY);
        if (secureContext != null) {
            return ImmutableSet.copyOf(secureContext.values());
        }
        else {
            return ImmutableSet.of();
        }
    }

    /**
     * Submits the job to salt-api using the class function and args.
     *
     * @return the jid of the submitted job
     * @throws HttpException
     *             if there was a communication failure with salt-api
     * @throws InterruptedException
     */
    protected String submitJob(SaltApiCapability capability, HttpClient client, String authToken, String minionId, Set<String> secureData) throws HttpException, IOException,
            SaltApiException, SaltTargettingMismatchException, InterruptedException {

        List<NameValuePair> args = ArgumentParser.DEFAULT_ARGUMENT_SPLITTER.parse(function);

        SaltApiRequest req = new SaltApiRequest();
        req.tgt = minionId;
        req.fun = args.get(0).getValue();

        List<NameValuePair> printableParams = Lists.newArrayList();
        printableParams.add(new BasicNameValuePair(SALT_API_FUNCTION_PARAM_NAME, req.fun));
        printableParams.add(new BasicNameValuePair(SALT_API_TARGET_PARAM_NAME, req.tgt));
        for (int i = 1; i < args.size(); i++) {
            String name = args.get(i).getName().equals("") ? SALT_API_ARGUMENTS_PARAM_NAME : args.get(i).getName();
            String value = args.get(i).getValue();

            if (name.equals(SALT_API_ARGUMENTS_PARAM_NAME))
                req.arg.add(value);
            else
                req.kwarg.put(name, value);

            for (String s : secureData) {
                value = StringUtils.replace(value, s, SECURE_OPTION_VALUE);
            }
            printableParams.add(new BasicNameValuePair(name, value));
        }




        StringEntity postEntity = new StringEntity(new Gson().toJson(req));
        //postEntity.setContentEncoding(CHAR_SET_ENCODING);
        //postEntity.setContentType(REQUEST_CONTENT_TYPE);

        HttpPost post = httpFactory.createHttpPost(saltEndpoint + MINION_RESOURCE);
        post.setHeader(SALT_AUTH_TOKEN_HEADER, authToken);
        post.setHeader(REQUEST_ACCEPT_HEADER_NAME, JSON_RESPONSE_ACCEPT_TYPE);
        post.setHeader("Content-Type", JSON_RESPONSE_ACCEPT_TYPE);
        post.setEntity(postEntity);
        
        logWrapper.debug("Submitting job with arguments [%s]", printableParams);
        logWrapper.info("Submitting job with salt-api endpoint: [%s]", post.getURI());
        HttpResponse response = retryExecutor.execute(logWrapper, client, post, numRetries, Predicates.<Integer>alwaysFalse());
        
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity entity = response.getEntity();
        try {
            String entityResponse = extractBodyFromEntity(entity);
            if (statusCode != HttpStatus.SC_ACCEPTED) {
                throw new HttpException(String.format("Expected response code %d, received %d. %s",
                        HttpStatus.SC_ACCEPTED, statusCode, entityResponse));
            } else {
                logWrapper.debug("Received response for job submission = %s", response);
                SaltInteractionHandler interactionHandler = capability.getSaltInteractionHandler();
                SaltApiResponseOutput saltOutput = interactionHandler.extractOutputForJobSubmissionResponse(entityResponse);
                if (saltOutput.getMinions().size() != 1) {
                    throw new SaltTargettingMismatchException(String.format(
                            "Expected minion delegation count of 1, was %d. Full minion string: (%s)", saltOutput
                                    .getMinions().size(), saltOutput.getMinions()));
                } else if (!saltOutput.getMinions().contains(minionId)) {
                    throw new SaltTargettingMismatchException(String.format(
                            "Minion dispatch mis-match. Expected:%s,  was:%s", minionId, saltOutput.getMinions()
                                    .toString()));
                }
                return saltOutput.getJid();
            }
        } finally {
            closeResource(entity);
            post.releaseConnection();
        }
    }

    protected void validate(String user, String password, INodeEntry entry) throws SaltStepValidationException {
        checkNotEmpty(SALT_API_END_POINT_OPTION_NAME, saltEndpoint, SaltApiNodeStepFailureReason.ARGUMENTS_MISSING,
                entry);
        checkNotEmpty(SALT_API_FUNCTION_OPTION_NAME, function, SaltApiNodeStepFailureReason.ARGUMENTS_MISSING, entry);
        checkNotEmpty(SALT_API_EAUTH_OPTION_NAME, eAuth, SaltApiNodeStepFailureReason.ARGUMENTS_MISSING, entry);
        checkNotEmpty(SALT_USER_OPTION_NAME, user, SaltApiNodeStepFailureReason.ARGUMENTS_MISSING, entry);
        checkNotEmpty(SALT_PASSWORD_OPTION_NAME, password, SaltApiNodeStepFailureReason.ARGUMENTS_MISSING, entry);

        UrlValidator urlValidator = new UrlValidator(endPointSchemes, UrlValidator.ALLOW_LOCAL_URLS);
        if (!urlValidator.isValid(saltEndpoint)) {
            throw new SaltStepValidationException(SALT_API_END_POINT_OPTION_NAME, String.format(
                    "%s is not a valid endpoint.", saltEndpoint), SaltApiNodeStepFailureReason.ARGUMENTS_INVALID,
                    entry.getNodename());
        }
    }

    protected String waitForJidResponse(HttpClient client, String authToken, String jid, String minionId)
            throws IOException, InterruptedException, SaltApiException {
        ExponentialBackoffTimer timer = timerFactory.newTimer(delayStep, maximumRetryDelay);
        String jidResource = String.format("%s%s/%s", saltEndpoint, JOBS_RESOURCE, jid);
        logWrapper.info("Polling for job status with salt-api endpoint: [%s]", jidResource);
        do {
            String response = extractOutputForJid(client, authToken, jid, minionId);
            if (response != null) {
                return response;
            }
            timer.waitForNext();
        } while (true);
    }

    /**
     * Extracts the minion job response by calling the job resource.
     * 
     * @return the host response or null if none is available encoded in json.
     * @throws SaltApiException
     *             if the salt-api response does not conform to the expected
     *             format.
     * @throws InterruptedException
     */
    protected String extractOutputForJid(HttpClient client, String authToken, String jid, String minionId)
            throws IOException, SaltApiException, InterruptedException {
        String jidResource = String.format("%s%s/%s", saltEndpoint, JOBS_RESOURCE, jid);
        HttpGet get = httpFactory.createHttpGet(jidResource);
        get.setHeader(SALT_AUTH_TOKEN_HEADER, authToken);
        get.setHeader(REQUEST_ACCEPT_HEADER_NAME, JSON_RESPONSE_ACCEPT_TYPE);
        
        HttpResponse response = retryExecutor.execute(logWrapper, client, get, numRetries);
        
        try {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                HttpEntity entity = response.getEntity();
                String entityResponse = extractBodyFromEntity(entity);
                Gson gson = new Gson();
                Map<String, List<Map<String, Object>>> result = gson.fromJson(entityResponse, JOB_RESPONSE_TYPE);
                List<Map<String, Object>> responses = result.get(SALT_OUTPUT_RETURN_KEY);
                if (responses.size() > 1) {
                    throw new SaltApiException("Too many responses received: " + response);
                } else if (responses.size() == 1) {
                    Map<String, Object> minionResponse = responses.get(0);
                    if (minionResponse.containsKey(minionId)) {
                        logWrapper.debug("Received response for jobs/%s = %s", jid, response);
                        Object responseObj = minionResponse.get(minionId);
                        return gson.toJson(responseObj);
                    }
                }
                return null;
            } else {
                return null;
            }
        } finally {
            closeResource(response.getEntity());
            get.releaseConnection();
        }
    }

    /**
     * Authenticates the given username/password with the given eauth system
     * against the salt-api endpoint
     * 
     * @param capability
     *            The {@link SaltApiCapability} that describes the supported features of the saltEndpoint 
     * @param user
     *            The user to auth with
     * @param password
     *            The password for the given user
     * @return X-Auth-Token for use in subsequent requests
     */
    protected String authenticate(final SaltApiCapability capability, HttpClient client, String user, String password) throws IOException, HttpException,
            InterruptedException {
        List<NameValuePair> params = Lists.newArrayListWithCapacity(3);
        params.add(new BasicNameValuePair(SALT_API_USERNAME_PARAM_NAME, user));
        params.add(new BasicNameValuePair(SALT_API_PASSWORD_PARAM_NAME, password));
        params.add(new BasicNameValuePair(SALT_API_EAUTH_PARAM_NAME, eAuth));
        UrlEncodedFormEntity postEntity = new UrlEncodedFormEntity(params, CHAR_SET_ENCODING);
        postEntity.setContentEncoding(CHAR_SET_ENCODING);
        postEntity.setContentType(REQUEST_CONTENT_TYPE);

        HttpPost post = httpFactory.createHttpPost(saltEndpoint + LOGIN_RESOURCE);
        post.setEntity(postEntity);
        
        logWrapper.info("Authenticating with salt-api endpoint: [%s]", post.getURI());
        HttpResponse response = retryExecutor.execute(logWrapper, client, post, numRetries, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input != capability.getLoginFailureResponseCode();
            }
        });

        try {
            int responseCode = response.getStatusLine().getStatusCode();
            if (responseCode == capability.getLoginSuccessResponseCode()) {
                return response.getHeaders(SALT_AUTH_TOKEN_HEADER)[0].getValue();
            } else if (responseCode == capability.getLoginFailureResponseCode()) {
                return null;
            } else {
                throw new HttpException(String.format("Unexpected failure interacting with salt-api %s", response
                        .getStatusLine().toString()));
            }
        } finally {
            closeResource(response.getEntity());
            post.releaseConnection();
        }
    }

    protected void logoutQuietly(HttpClient client, String authToken) {
        String logoutResource = String.format("%s%s", saltEndpoint, LOGOUT_RESOURCE);
        HttpGet get = httpFactory.createHttpGet(logoutResource);
        get.setHeader(SALT_AUTH_TOKEN_HEADER, authToken);
        
        logWrapper.info("Logging out with salt-api endpoint: [%s]", get.getURI());
        
        try {
            retryExecutor.execute(logWrapper, client, get, numRetries);
        } catch (IOException e) {
            logWrapper.warn("Encountered exception (%s) while trying to logout. Ignoring...", e.getMessage());
        } catch (InterruptedException e) {
            logWrapper.warn("Interrupted while trying to logout.");
            Thread.currentThread().interrupt();
        }
    }

    protected SaltApiCapability getSaltApiCapability() {
        return StringUtils.isBlank(saltApiVersion) ? capabilityRegistry.getLatest() : capabilityRegistry
                .getCapability(saltApiVersion);
    }

    protected void setLogWrapper(PluginLogger logger) {
        logWrapper = new LogWrapper(logger);
    }

    // -- Isolating so powermock doesn't kill permgen --
    protected String extractBodyFromEntity(HttpEntity entity) throws ParseException, IOException {
        return EntityUtils.toString(entity);
    }

    protected void closeResource(HttpEntity entity) {
        EntityUtils.consumeQuietly(entity);
    }
}
