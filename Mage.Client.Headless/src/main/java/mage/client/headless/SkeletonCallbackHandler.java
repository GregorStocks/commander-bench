package mage.client.headless;

import mage.choices.Choice;
import mage.constants.PlayerAction;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Session;
import mage.view.AbilityPickerView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.TableClientMessage;
import mage.view.UserRequestMessage;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback handler for the skeleton headless client.
 * Always passes priority and chooses the first available option when forced to make a choice.
 */
public class SkeletonCallbackHandler {

    private static final Logger logger = Logger.getLogger(SkeletonCallbackHandler.class);

    private final SkeletonMageClient client;
    private Session session;
    private final Map<UUID, UUID> activeGames = new ConcurrentHashMap<>(); // gameId -> playerId

    public SkeletonCallbackHandler(SkeletonMageClient client) {
        this.client = client;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public void reset() {
        activeGames.clear();
    }

    public void handleCallback(ClientCallback callback) {
        try {
            callback.decompressData();
            UUID objectId = callback.getObjectId();
            logger.info("[" + client.getUsername() + "] Callback received: " + callback.getMethod());

            switch (callback.getMethod()) {
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
                    handleGameAsk(objectId, callback);
                    break;

                case GAME_SELECT:
                    handleGameSelect(objectId, callback);
                    break;

                case GAME_TARGET:
                    handleGameTarget(objectId, callback);
                    break;

                case GAME_CHOOSE_ABILITY:
                    handleGameChooseAbility(objectId, callback);
                    break;

                case GAME_CHOOSE_CHOICE:
                    handleGameChooseChoice(objectId, callback);
                    break;

                case GAME_CHOOSE_PILE:
                    handleGameChoosePile(objectId, callback);
                    break;

                case GAME_PLAY_MANA:
                case GAME_PLAY_XMANA:
                    handleGamePlayMana(objectId, callback);
                    break;

                case GAME_GET_AMOUNT:
                    handleGameGetAmount(objectId, callback);
                    break;

                case GAME_GET_MULTI_AMOUNT:
                    handleGameGetMultiAmount(objectId, callback);
                    break;

                case GAME_OVER:
                    handleGameOver(objectId, callback);
                    break;

                case END_GAME_INFO:
                    logger.info("[" + client.getUsername() + "] End game info received");
                    break;

                case CHATMESSAGE:
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
                    logger.debug("[" + client.getUsername() + "] Unhandled callback: " + callback.getMethod());
            }
        } catch (Exception e) {
            logger.error("[" + client.getUsername() + "] Error handling callback: " + callback.getMethod(), e);
        }
    }

    private void handleStartGame(UUID gameId, ClientCallback callback) {
        TableClientMessage message = (TableClientMessage) callback.getData();
        UUID playerId = message.getPlayerId();
        activeGames.put(gameId, playerId);
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
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameSelect(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Select: \"" + message.getMessage() + "\" -> PASS");
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameTarget(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        boolean required = message.isFlag();
        Set<UUID> targets = message.getTargets();

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
        session.sendPlayerBoolean(gameId, true);
    }

    private void handleGamePlayMana(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        logger.info("[" + client.getUsername() + "] Mana: \"" + message.getMessage() + "\" -> CANCEL/AUTO");
        session.sendPlayerBoolean(gameId, false);
    }

    private void handleGameGetAmount(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        int min = message.getMin();
        logger.info("[" + client.getUsername() + "] Amount: \"" + message.getMessage() + "\" (min=" + min + ", max=" + message.getMax() + ") -> " + min);
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
