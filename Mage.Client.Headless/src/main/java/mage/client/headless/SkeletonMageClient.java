package mage.client.headless;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Session;
import mage.utils.MageVersion;
import org.apache.log4j.Logger;

/**
 * Headless MageClient implementation for bot/AI players.
 * Delegates callback handling to SkeletonCallbackHandler.
 */
public class SkeletonMageClient implements MageClient {

    private static final Logger logger = Logger.getLogger(SkeletonMageClient.class);
    private static final MageVersion version = new MageVersion(SkeletonMageClient.class);

    private final String username;
    private Session session;
    private final SkeletonCallbackHandler callbackHandler;
    private volatile boolean running = true;

    public SkeletonMageClient(String username) {
        this.username = username;
        this.callbackHandler = new SkeletonCallbackHandler(this);
    }

    public void setSession(Session session) {
        this.session = session;
        this.callbackHandler.setSession(session);
    }

    public Session getSession() {
        return session;
    }

    public String getUsername() {
        return username;
    }

    public SkeletonCallbackHandler getCallbackHandler() {
        return callbackHandler;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
    }

    @Override
    public MageVersion getVersion() {
        return version;
    }

    @Override
    public void connected(String message) {
        logger.info("[" + username + "] Connected: " + message);
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        logger.info("[" + username + "] Disconnected (askToReconnect=" + askToReconnect + ", keepSession=" + keepMySessionActive + ")");
        running = false;
    }

    @Override
    public void showMessage(String message) {
        logger.info("[" + username + "] Message: " + message);
    }

    @Override
    public void showError(String message) {
        logger.error("[" + username + "] Error: " + message);
    }

    @Override
    public void onNewConnection() {
        logger.info("[" + username + "] New connection established");
        callbackHandler.reset();
    }

    @Override
    public void onCallback(ClientCallback callback) {
        callbackHandler.handleCallback(callback);
    }
}
