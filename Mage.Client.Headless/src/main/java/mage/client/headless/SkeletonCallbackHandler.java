package mage.client.headless;

import mage.choices.Choice;
import mage.constants.PlayerAction;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.remote.Session;
import mage.view.AbilityPickerView;
import mage.view.ChatMessage;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.TableClientMessage;
import mage.view.UserRequestMessage;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback handler for the skeleton headless client.
 * Supports two modes:
 * - potato mode (default): Always passes priority and chooses the first available option
 * - MCP mode (sleepwalker): Stores pending actions for external client to handle via MCP
 */
public class SkeletonCallbackHandler {

    private static final Logger logger = Logger.getLogger(SkeletonCallbackHandler.class);
    private static final int ACTION_DELAY_MS = 500;

    private final SkeletonMageClient client;
    private Session session;
    private final Map<UUID, UUID> activeGames = new ConcurrentHashMap<>(); // gameId -> playerId
    private final Map<UUID, UUID> gameChatIds = new ConcurrentHashMap<>(); // gameId -> chatId

    // MCP mode fields
    private volatile boolean mcpMode = false;
    private volatile PendingAction pendingAction = null;
    private final StringBuilder gameLog = new StringBuilder();
    private volatile UUID currentGameId = null;

    public SkeletonCallbackHandler(SkeletonMageClient client) {
        this.client = client;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public void setMcpMode(boolean enabled) {
        this.mcpMode = enabled;
        logger.info("[" + client.getUsername() + "] MCP mode " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isMcpMode() {
        return mcpMode;
    }

    public void reset() {
        activeGames.clear();
        gameChatIds.clear();
        pendingAction = null;
        currentGameId = null;
        synchronized (gameLog) {
            gameLog.setLength(0);
        }
    }

    private void sleepBeforeAction() {
        try {
            Thread.sleep(ACTION_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // MCP mode methods

    public boolean isActionPending() {
        return pendingAction != null;
    }

    public Map<String, Object> getPendingActionInfo() {
        Map<String, Object> info = new HashMap<>();
        PendingAction action = pendingAction;
        if (action != null) {
            info.put("action_pending", true);
            info.put("game_id", action.getGameId().toString());
            info.put("action_type", action.getMethod().name());
            info.put("message", action.getMessage());
        } else {
            info.put("action_pending", false);
        }
        return info;
    }

    public Map<String, Object> executeDefaultAction() {
        Map<String, Object> result = new HashMap<>();
        PendingAction action = pendingAction;
        if (action == null) {
            result.put("success", false);
            result.put("error", "No pending action");
            return result;
        }

        // Clear pending action first
        pendingAction = null;

        // Execute the default response based on action type
        UUID gameId = action.getGameId();
        ClientCallbackMethod method = action.getMethod();
        Object data = action.getData();

        result.put("success", true);
        result.put("action_type", method.name());

        switch (method) {
            case GAME_ASK:
            case GAME_SELECT:
                session.sendPlayerBoolean(gameId, false);
                result.put("action_taken", "passed_priority");
                break;

            case GAME_TARGET:
                GameClientMessage targetMsg = (GameClientMessage) data;
                boolean required = targetMsg.isFlag();
                Set<UUID> targets = targetMsg.getTargets();
                if (required && targets != null && !targets.isEmpty()) {
                    UUID firstTarget = targets.iterator().next();
                    session.sendPlayerUUID(gameId, firstTarget);
                    result.put("action_taken", "selected_first_target");
                } else {
                    session.sendPlayerBoolean(gameId, false);
                    result.put("action_taken", "cancelled");
                }
                break;

            case GAME_CHOOSE_ABILITY:
                AbilityPickerView picker = (AbilityPickerView) data;
                Map<UUID, String> abilityChoices = picker.getChoices();
                if (abilityChoices != null && !abilityChoices.isEmpty()) {
                    UUID firstChoice = abilityChoices.keySet().iterator().next();
                    session.sendPlayerUUID(gameId, firstChoice);
                    result.put("action_taken", "selected_first_ability");
                } else {
                    session.sendPlayerUUID(gameId, null);
                    result.put("action_taken", "no_abilities");
                }
                break;

            case GAME_CHOOSE_CHOICE:
                GameClientMessage choiceMsg = (GameClientMessage) data;
                Choice choice = choiceMsg.getChoice();
                if (choice != null) {
                    if (choice.isKeyChoice()) {
                        Map<String, String> keyChoices = choice.getKeyChoices();
                        if (keyChoices != null && !keyChoices.isEmpty()) {
                            String firstKey = keyChoices.keySet().iterator().next();
                            session.sendPlayerString(gameId, firstKey);
                            result.put("action_taken", "selected_first_key_choice");
                        } else {
                            session.sendPlayerString(gameId, null);
                            result.put("action_taken", "no_choices");
                        }
                    } else {
                        Set<String> choices = choice.getChoices();
                        if (choices != null && !choices.isEmpty()) {
                            String firstChoice = choices.iterator().next();
                            session.sendPlayerString(gameId, firstChoice);
                            result.put("action_taken", "selected_first_choice");
                        } else {
                            session.sendPlayerString(gameId, null);
                            result.put("action_taken", "no_choices");
                        }
                    }
                } else {
                    session.sendPlayerString(gameId, null);
                    result.put("action_taken", "null_choice");
                }
                break;

            case GAME_CHOOSE_PILE:
                session.sendPlayerBoolean(gameId, true);
                result.put("action_taken", "selected_pile_1");
                break;

            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
                session.sendPlayerBoolean(gameId, false);
                result.put("action_taken", "cancelled_mana");
                break;

            case GAME_GET_AMOUNT:
                GameClientMessage amountMsg = (GameClientMessage) data;
                int min = amountMsg.getMin();
                session.sendPlayerInteger(gameId, min);
                result.put("action_taken", "selected_min_amount");
                result.put("amount", min);
                break;

            case GAME_GET_MULTI_AMOUNT:
                GameClientMessage multiMsg = (GameClientMessage) data;
                int count = multiMsg.getMessages() != null ? multiMsg.getMessages().size() : 0;
                int multiMin = multiMsg.getMin();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(i == 0 ? multiMin : 0);
                }
                session.sendPlayerString(gameId, sb.toString());
                result.put("action_taken", "selected_min_multi_amount");
                break;

            default:
                result.put("success", false);
                result.put("error", "Unknown action type: " + method);
        }

        return result;
    }

    public String getGameLog(int maxChars) {
        synchronized (gameLog) {
            if (maxChars <= 0 || maxChars >= gameLog.length()) {
                return gameLog.toString();
            }
            return gameLog.substring(gameLog.length() - maxChars);
        }
    }

    public int getGameLogLength() {
        synchronized (gameLog) {
            return gameLog.length();
        }
    }

    public boolean sendChatMessage(String message) {
        UUID gameId = currentGameId;
        if (gameId == null) {
            logger.warn("[" + client.getUsername() + "] Cannot send chat: no active game");
            return false;
        }
        UUID chatId = gameChatIds.get(gameId);
        if (chatId == null) {
            logger.warn("[" + client.getUsername() + "] Cannot send chat: no chat ID for game " + gameId);
            return false;
        }
        return session.sendChatMessage(chatId, message);
    }

    public void handleCallback(ClientCallback callback) {
        try {
            callback.decompressData();
            UUID objectId = callback.getObjectId();
            ClientCallbackMethod method = callback.getMethod();
            logger.info("[" + client.getUsername() + "] Callback received: " + method);

            switch (method) {
                case START_GAME:
                    handleStartGame(objectId, callback);
                    break;

                case GAME_INIT:
                    handleGameInit(objectId, callback);
                    break;

                case GAME_UPDATE:
                case GAME_UPDATE_AND_INFORM:
                    logGameState(objectId, callback);
                    break;

                case GAME_ASK:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameAsk(objectId, callback);
                    }
                    break;

                case GAME_SELECT:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameSelect(objectId, callback);
                    }
                    break;

                case GAME_TARGET:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameTarget(objectId, callback);
                    }
                    break;

                case GAME_CHOOSE_ABILITY:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameChooseAbility(objectId, callback);
                    }
                    break;

                case GAME_CHOOSE_CHOICE:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameChooseChoice(objectId, callback);
                    }
                    break;

                case GAME_CHOOSE_PILE:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameChoosePile(objectId, callback);
                    }
                    break;

                case GAME_PLAY_MANA:
                case GAME_PLAY_XMANA:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGamePlayMana(objectId, callback);
                    }
                    break;

                case GAME_GET_AMOUNT:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameGetAmount(objectId, callback);
                    }
                    break;

                case GAME_GET_MULTI_AMOUNT:
                    if (mcpMode) {
                        storePendingAction(objectId, method, callback);
                    } else {
                        handleGameGetMultiAmount(objectId, callback);
                    }
                    break;

                case GAME_OVER:
                    handleGameOver(objectId, callback);
                    break;

                case END_GAME_INFO:
                    logger.info("[" + client.getUsername() + "] End game info received");
                    break;

                case CHATMESSAGE:
                    handleChatMessage(callback);
                    break;

                case SERVER_MESSAGE:
                case GAME_ERROR:
                case GAME_INFORM_PERSONAL:
                case JOINED_TABLE:
                    logEvent(callback);
                    break;

                case USER_REQUEST_DIALOG:
                    handleUserRequestDialog(callback);
                    break;

                default:
                    logger.debug("[" + client.getUsername() + "] Unhandled callback: " + method);
            }
        } catch (Exception e) {
            logger.error("[" + client.getUsername() + "] Error handling callback: " + callback.getMethod(), e);
        }
    }

    private void storePendingAction(UUID gameId, ClientCallbackMethod method, ClientCallback callback) {
        Object data = callback.getData();
        String message = extractMessage(data);
        pendingAction = new PendingAction(gameId, method, data, message);
        logger.info("[" + client.getUsername() + "] Stored pending action: " + method + " - " + message);
    }

    private String extractMessage(Object data) {
        if (data instanceof GameClientMessage) {
            GameClientMessage msg = (GameClientMessage) data;
            if (msg.getMessage() != null) {
                return msg.getMessage();
            }
            if (msg.getChoice() != null && msg.getChoice().getMessage() != null) {
                return msg.getChoice().getMessage();
            }
        } else if (data instanceof AbilityPickerView) {
            AbilityPickerView picker = (AbilityPickerView) data;
            return picker.getMessage();
        }
        return "";
    }

    private void handleChatMessage(ClientCallback callback) {
        Object data = callback.getData();
        if (data instanceof ChatMessage) {
            ChatMessage chatMsg = (ChatMessage) data;
            // Only log GAME type messages to the game log
            if (chatMsg.getMessageType() == ChatMessage.MessageType.GAME) {
                String logEntry = chatMsg.getMessage();
                if (logEntry != null && !logEntry.isEmpty()) {
                    synchronized (gameLog) {
                        if (gameLog.length() > 0) {
                            gameLog.append("\n");
                        }
                        gameLog.append(logEntry);
                    }
                }
            }
            logger.debug("[" + client.getUsername() + "] Chat: " + chatMsg.getMessage());
        } else {
            logEvent(callback);
        }
    }

    private void handleStartGame(UUID gameId, ClientCallback callback) {
        TableClientMessage message = (TableClientMessage) callback.getData();
        UUID playerId = message.getPlayerId();
        activeGames.put(gameId, playerId);
        currentGameId = gameId;

        // Get chat ID for this game
        session.getGameChatId(gameId).ifPresent(chatId -> {
            gameChatIds.put(gameId, chatId);
            logger.info("[" + client.getUsername() + "] Game chat ID: " + chatId);
        });

        logger.info("[" + client.getUsername() + "] Game started: gameId=" + gameId + ", playerId=" + playerId);
    }

    private void handleGameInit(UUID gameId, ClientCallback callback) {
        GameView gameView = (GameView) callback.getData();
        logger.info("[" + client.getUsername() + "] Game initialized: " + gameView.getPlayers().size() + " players");
    }

    private void logGameState(UUID gameId, ClientCallback callback) {
        Object data = callback.getData();
        if (data instanceof GameView) {
            GameView gameView = (GameView) data;
            logger.debug("[" + client.getUsername() + "] Game update: turn " + gameView.getTurn() +
                    ", phase " + gameView.getPhase() + ", active player " + gameView.getActivePlayerName());
        } else if (data instanceof GameClientMessage) {
            GameClientMessage message = (GameClientMessage) data;
            GameView gameView = message.getGameView();
            if (gameView != null) {
                logger.debug("[" + client.getUsername() + "] Game inform: " + message.getMessage());
            }
        }
    }

    private void handleGameAsk(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Ask: \"" + message.getMessage() + "\" -> NO");
        sleepBeforeAction();
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameSelect(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Select: \"" + message.getMessage() + "\" -> PASS");
        sleepBeforeAction();
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameTarget(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        boolean required = message.isFlag();
        Set<UUID> targets = message.getTargets();

        sleepBeforeAction();
        if (required && targets != null && !targets.isEmpty()) {
            UUID firstTarget = targets.iterator().next();
            logger.info("[" + client.getUsername() + "] Target (required): \"" + message.getMessage() + "\" -> " + firstTarget);
            session.sendPlayerUUID(gameId, firstTarget);
        } else {
            logger.info("[" + client.getUsername() + "] Target (optional): \"" + message.getMessage() + "\" -> CANCEL");
            session.sendPlayerBoolean(gameId, false);
        }
    }

    private void handleGameChooseAbility(UUID gameId, ClientCallback callback) {
        AbilityPickerView picker = (AbilityPickerView) callback.getData();
        Map<UUID, String> choices = picker.getChoices();

        sleepBeforeAction();
        if (choices != null && !choices.isEmpty()) {
            UUID firstChoice = choices.keySet().iterator().next();
            String choiceText = choices.get(firstChoice);
            logger.info("[" + client.getUsername() + "] Ability: \"" + picker.getMessage() + "\" -> " + choiceText);
            session.sendPlayerUUID(gameId, firstChoice);
        } else {
            logger.warn("[" + client.getUsername() + "] Ability: no choices available, sending null");
            session.sendPlayerUUID(gameId, null);
        }
    }

    private void handleGameChooseChoice(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        Choice choice = message.getChoice();

        if (choice == null) {
            logger.warn("[" + client.getUsername() + "] Choice: null choice object");
            session.sendPlayerString(gameId, null);
            return;
        }

        sleepBeforeAction();
        if (choice.isKeyChoice()) {
            Map<String, String> keyChoices = choice.getKeyChoices();
            if (keyChoices != null && !keyChoices.isEmpty()) {
                String firstKey = keyChoices.keySet().iterator().next();
                logger.info("[" + client.getUsername() + "] Choice (key): \"" + choice.getMessage() + "\" -> " + firstKey + " (" + keyChoices.get(firstKey) + ")");
                session.sendPlayerString(gameId, firstKey);
            } else {
                logger.warn("[" + client.getUsername() + "] Choice (key): no choices available");
                session.sendPlayerString(gameId, null);
            }
        } else {
            Set<String> choices = choice.getChoices();
            if (choices != null && !choices.isEmpty()) {
                String firstChoice = choices.iterator().next();
                logger.info("[" + client.getUsername() + "] Choice: \"" + choice.getMessage() + "\" -> " + firstChoice);
                session.sendPlayerString(gameId, firstChoice);
            } else {
                logger.warn("[" + client.getUsername() + "] Choice: no choices available");
                session.sendPlayerString(gameId, null);
            }
        }
    }

    private void handleGameChoosePile(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Pile: \"" + message.getMessage() + "\" -> pile 1");
        sleepBeforeAction();
        session.sendPlayerBoolean(gameId, true);
    }

    private void handleGamePlayMana(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Mana: \"" + message.getMessage() + "\" -> CANCEL/AUTO");
        sleepBeforeAction();
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameGetAmount(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        int min = message.getMin();
        logger.info("[" + client.getUsername() + "] Amount: \"" + message.getMessage() + "\" (min=" + min + ", max=" + message.getMax() + ") -> " + min);
        sleepBeforeAction();
        session.sendPlayerInteger(gameId, min);
    }

    private void handleGameGetMultiAmount(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        int count = message.getMessages() != null ? message.getMessages().size() : 0;
        int min = message.getMin();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(i == 0 ? min : 0);
        }

        String result = sb.toString();
        logger.info("[" + client.getUsername() + "] MultiAmount: " + count + " values, min=" + min + " -> " + result);
        sleepBeforeAction();
        session.sendPlayerString(gameId, result);
    }

    private void handleGameOver(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        activeGames.remove(gameId);
        logger.info("[" + client.getUsername() + "] Game over: " + message.getMessage());

        if (activeGames.isEmpty()) {
            logger.info("[" + client.getUsername() + "] No more active games, stopping client");
            client.stop();
        }
    }

    private void handleUserRequestDialog(ClientCallback callback) {
        UserRequestMessage request = (UserRequestMessage) callback.getData();
        // Auto-accept hand permission requests from observers
        if (request.getButton1Action() == PlayerAction.ADD_PERMISSION_TO_SEE_HAND_CARDS) {
            UUID gameId = request.getGameId();
            UUID relatedUserId = request.getRelatedUserId();
            logger.info("[" + client.getUsername() + "] Auto-granting hand permission to " + request.getRelatedUserName());
            session.sendPlayerAction(PlayerAction.ADD_PERMISSION_TO_SEE_HAND_CARDS, gameId, relatedUserId);
        } else {
            logger.debug("[" + client.getUsername() + "] Ignoring user request dialog: " + request.getTitle());
        }
    }

    private void logEvent(ClientCallback callback) {
        logger.debug("[" + client.getUsername() + "] Event: " + callback.getMethod() + " - " + callback.getData());
    }
}
