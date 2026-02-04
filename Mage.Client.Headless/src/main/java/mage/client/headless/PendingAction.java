package mage.client.headless;

import mage.interfaces.callback.ClientCallbackMethod;

import java.util.UUID;

/**
 * Encapsulates a pending game action that requires a response.
 * Used in MCP mode to track what action the external client needs to handle.
 */
public class PendingAction {

    private final UUID gameId;
    private final ClientCallbackMethod method;
    private final Object data;
    private final String message;

    public PendingAction(UUID gameId, ClientCallbackMethod method, Object data, String message) {
        this.gameId = gameId;
        this.method = method;
        this.data = data;
        this.message = message;
    }

    public UUID getGameId() {
        return gameId;
    }

    public ClientCallbackMethod getMethod() {
        return method;
    }

    public Object getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "PendingAction{" +
                "gameId=" + gameId +
                ", method=" + method +
                ", message='" + message + '\'' +
                '}';
    }
}
