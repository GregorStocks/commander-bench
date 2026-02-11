package mage.client.headless;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.choices.Choice;
import mage.constants.ManaType;
import mage.constants.PlayerAction;
import mage.constants.SubType;
import mage.constants.SubTypeSet;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.remote.Session;
import mage.view.AbilityPickerView;
import mage.view.CommandObjectView;
import mage.view.CounterView;
import mage.view.CardsView;
import mage.view.CardView;
import mage.view.ChatMessage;
import mage.view.CombatGroupView;
import mage.view.ExileView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.TableClientMessage;
import mage.view.UserRequestMessage;
import mage.players.PlayableObjectsList;
import mage.players.PlayableObjectStats;
import mage.util.MultiAmountMessage;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Callback handler for the skeleton headless client.
 * Supports multiple modes:
 * - potato mode (default): Always passes priority and chooses the first available option
 * - staller mode: Same decisions as potato, but intentionally delayed and kept alive between games
 * - MCP mode (sleepwalker): Stores pending actions for external client to handle via MCP
 */
public class SkeletonCallbackHandler {

    private static final Logger logger = Logger.getLogger(SkeletonCallbackHandler.class);
    private static final int DEFAULT_ACTION_DELAY_MS = 500;
    private static final int MAX_GAME_LOG_CHARS = 5 * 1024 * 1024; // 5MB cap on in-memory game log buffer

    // Regex patterns to detect colored mana symbols inside braces, including hybrid/phyrexian variants.
    // Same approach as ManaUtil.java — \x7b = {, \x7d = }, .{0,2} allows up to 2 chars on each side.
    // Matches {W}, {W/U}, {W/P}, {W/U/P}, {2/W}, {C/W}, etc.
    private static final Pattern REGEX_WHITE = Pattern.compile("\\x7b.{0,2}W.{0,2}\\x7d");
    private static final Pattern REGEX_BLUE = Pattern.compile("\\x7b.{0,2}U.{0,2}\\x7d");
    private static final Pattern REGEX_BLACK = Pattern.compile("\\x7b.{0,2}B.{0,2}\\x7d");
    private static final Pattern REGEX_RED = Pattern.compile("\\x7b.{0,2}R.{0,2}\\x7d");
    private static final Pattern REGEX_GREEN = Pattern.compile("\\x7b.{0,2}G.{0,2}\\x7d");
    private static final Pattern REGEX_COLORLESS = Pattern.compile("\\x7b.{0,2}C.{0,2}\\x7d");
    // Pattern to match "TURN <number>" at the start of game log messages
    private static final Pattern TURN_MSG_PATTERN = Pattern.compile("^TURN \\d+");

    private final SkeletonMageClient client;
    private Session session;
    private final Map<UUID, UUID> activeGames = new ConcurrentHashMap<>(); // gameId -> playerId
    private final Map<UUID, UUID> gameChatIds = new ConcurrentHashMap<>(); // gameId -> chatId

    // MCP mode fields
    private volatile boolean mcpMode = false;
    private volatile int actionDelayMs = DEFAULT_ACTION_DELAY_MS;
    private volatile int actionsProcessed = 0;
    private static final int STALLER_WARMUP_ACTIONS = 20;
    private volatile boolean keepAliveAfterGame = false;
    private volatile PendingAction pendingAction = null;
    private final Object actionLock = new Object(); // For wait_for_action blocking
    private final StringBuilder gameLog = new StringBuilder();
    private int gameLogTrimmedChars = 0; // tracks chars trimmed from front so offset-based access stays valid
    private volatile UUID currentGameId = null;
    private volatile GameView lastGameView = null;
    private final RoundTracker roundTracker = new RoundTracker();
    private volatile List<Object> lastChoices = null; // Index→UUID/String mapping for choose_action
    private final Set<UUID> failedManaCasts = ConcurrentHashMap.newKeySet(); // Spells that failed mana payment (avoid retry loops)
    private volatile int lastTurnNumber = -1; // For clearing failedManaCasts on turn change
    private volatile int interactionsThisTurn = 0; // Generic loop detection: count model interactions per turn
    private volatile int landsPlayedThisTurn = 0; // Track land plays for land_drops_used hint
    private volatile int maxInteractionsPerTurn = 25; // Configurable per-model; after this many, auto-pass rest of turn
    private volatile DeckCardLists deckList = null; // Original decklist for get_my_decklist
    private volatile String errorLogPath = null; // Path to write errors to (set via system property)
    private volatile String skeletonLogPath = null; // Path to write skeleton JSONL dump
    private final List<String> unseenChat = new ArrayList<>(); // Chat messages from other players not yet shown to LLM
    private volatile boolean playerDead = false; // Set when we see "{name} has lost the game" in chat
    private volatile String lastChatMessage = null; // For deduplicating outgoing chat
    private volatile long lastChatTimeMs = 0; // Timestamp of last outgoing chat
    private static final long CHAT_DEDUP_WINDOW_MS = 30_000; // Suppress identical messages within 30s

    // Lost response retry: track last response sent from chooseAction so we can
    // re-send if the server discards it due to the waitResponseOpen race condition
    // (see HumanPlayer.java:196). This happens when fireSelectTargetEvent blocks
    // the game thread on a slow/disconnected player, and our response arrives before
    // the game thread reaches waitForResponse().
    private enum ResponseType { UUID, BOOLEAN, STRING, INTEGER, MANA_TYPE }
    private volatile long lastResponseSentAt = 0;
    private volatile UUID lastResponseGameId;
    private volatile ResponseType lastResponseType;
    private volatile Object lastResponseValue;      // UUID, Boolean, String, Integer, or ManaType
    private volatile UUID lastResponseManaPlayerId; // only for MANA_TYPE
    private volatile boolean lastResponseRetried = false;
    private static final long LOST_RESPONSE_RETRY_MS = 25_000; // retry after 25s (server discards after 30s)
    private static final ZoneId LOG_TZ = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public SkeletonCallbackHandler(SkeletonMageClient client) {
        this.client = client;
    }

    public void setErrorLogPath(String path) {
        this.errorLogPath = path;
    }

    public void setSkeletonLogPath(String path) {
        this.skeletonLogPath = path;
    }

    /**
     * Append an error line to the error log file (if configured).
     * Also logs via log4j as usual.
     */
    void logError(String msg) {
        logger.error(msg);
        String path = errorLogPath;
        if (path != null) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(path, true))) {
                pw.println("[" + ZonedDateTime.now(LOG_TZ).format(TIME_FMT) + "] [mcp] " + msg);
            } catch (IOException e) {
                logger.warn("Failed to write to error log: " + e.getMessage());
            }
        }
    }

    /**
     * Write a skeleton event to the JSONL dump file (data hoarding).
     * Each line is a compact JSON object with timestamp, callback method, and relevant data.
     */
    private void logSkeletonEvent(ClientCallbackMethod method, String summary) {
        String path = skeletonLogPath;
        if (path == null) {
            return;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, true))) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ts\":\"").append(ZonedDateTime.now(LOG_TZ).format(TIME_FMT)).append("\"");
            sb.append(",\"method\":\"").append(method.name()).append("\"");
            if (summary != null && !summary.isEmpty()) {
                // Escape JSON string
                sb.append(",\"data\":").append(escapeJsonString(summary));
            }
            sb.append("}");
            pw.println(sb.toString());
        } catch (IOException e) {
            logger.debug("Failed to write skeleton log: " + e.getMessage());
        }
    }

    /**
     * Escape a string for JSON embedding. Returns a quoted JSON string.
     */
    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Build a compact one-line summary of game state for skeleton JSONL dump.
     */
    private String buildSkeletonStateSummary() {
        GameView gv = lastGameView;
        if (gv == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("T").append(roundTracker.getGameRound());
        if (gv.getPhase() != null) sb.append(" ").append(gv.getPhase());
        sb.append(" | ");
        UUID gameId = currentGameId; // snapshot volatile to prevent TOCTOU race
        UUID myPlayerId = gameId != null ? activeGames.get(gameId) : null;
        for (PlayerView p : gv.getPlayers()) {
            boolean isMe = p.getPlayerId().equals(myPlayerId);
            sb.append(p.getName());
            if (isMe) sb.append("(me)");
            sb.append(":").append(p.getLife()).append("hp");
            sb.append(",").append(p.getHandCount()).append("h");
            sb.append(",").append(p.getBattlefield() != null ? p.getBattlefield().size() : 0).append("bf");
            sb.append(" | ");
        }
        // My hand
        if (gv.getMyHand() != null && !gv.getMyHand().isEmpty()) {
            sb.append("Hand:[");
            boolean first = true;
            for (CardView card : gv.getMyHand().values()) {
                if (!first) sb.append(",");
                sb.append(card.getDisplayName());
                first = false;
            }
            sb.append("]");
        }
        return sb.toString();
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

    public void setActionDelayMs(int actionDelayMs) {
        this.actionDelayMs = Math.max(0, actionDelayMs);
        logger.info("[" + client.getUsername() + "] action delay set to " + this.actionDelayMs + " ms");
    }

    public void setKeepAliveAfterGame(boolean keepAliveAfterGame) {
        this.keepAliveAfterGame = keepAliveAfterGame;
        logger.info("[" + client.getUsername() + "] keepAliveAfterGame=" + keepAliveAfterGame);
    }

    public void setDeckList(DeckCardLists deckList) {
        this.deckList = deckList;
    }

    public void setMaxInteractionsPerTurn(int max) {
        this.maxInteractionsPerTurn = Math.max(5, max);
        logger.info("[" + client.getUsername() + "] maxInteractionsPerTurn set to " + this.maxInteractionsPerTurn);
    }

    public void reset() {
        activeGames.clear();
        gameChatIds.clear();
        pendingAction = null;
        currentGameId = null;
        lastGameView = null;
        lastChoices = null;
        actionsProcessed = 0;
        synchronized (gameLog) {
            gameLog.setLength(0);
            gameLogTrimmedChars = 0;
        }
    }

    private void sleepBeforeAction() {
        int delay = actionDelayMs;
        if (actionsProcessed < STALLER_WARMUP_ACTIONS) {
            delay = Math.min(delay, DEFAULT_ACTION_DELAY_MS);
            actionsProcessed++;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Lost response retry helpers

    private void trackSentResponse(UUID gameId, ResponseType type, Object value, UUID manaPlayerId) {
        lastResponseGameId = gameId;
        lastResponseType = type;
        lastResponseValue = value;
        lastResponseManaPlayerId = manaPlayerId;
        lastResponseRetried = false;
        lastResponseSentAt = System.currentTimeMillis();
    }

    private void clearTrackedResponse() {
        lastResponseSentAt = 0;
    }

    /**
     * If we sent a response >25s ago and haven't received a new callback,
     * the server may have discarded our response. Re-send it once.
     * Returns true if a retry was attempted.
     */
    private boolean retryLastResponseIfLost() {
        if (lastResponseSentAt == 0 || lastResponseRetried) return false;
        long age = System.currentTimeMillis() - lastResponseSentAt;
        if (age < LOST_RESPONSE_RETRY_MS) return false;

        lastResponseRetried = true;
        UUID gameId = lastResponseGameId;
        logger.warn("[" + client.getUsername() + "] Retrying suspected lost response"
            + " (age=" + age + "ms, type=" + lastResponseType + ")");

        switch (lastResponseType) {
            case UUID:      session.sendPlayerUUID(gameId, (java.util.UUID) lastResponseValue); break;
            case BOOLEAN:   session.sendPlayerBoolean(gameId, (Boolean) lastResponseValue); break;
            case STRING:    session.sendPlayerString(gameId, (String) lastResponseValue); break;
            case INTEGER:   session.sendPlayerInteger(gameId, (Integer) lastResponseValue); break;
            case MANA_TYPE: session.sendPlayerManaType(gameId, lastResponseManaPlayerId, (ManaType) lastResponseValue); break;
        }
        return true;
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
            info.put("action_type", action.getMethod().name());
            info.put("message", action.getMessage());
        } else {
            info.put("action_pending", false);
        }
        info.put("game_over", activeGames.isEmpty());
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

        // Clear pending action only if it hasn't been overwritten by a new callback.
        synchronized (actionLock) {
            if (pendingAction == action) {
                pendingAction = null;
            }
        }

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

            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
                // Auto-tap failed; default action is to cancel the spell
                session.sendPlayerBoolean(gameId, false);
                result.put("action_taken", "cancelled_mana");
                break;

            case GAME_TARGET:
                GameClientMessage targetMsg = (GameClientMessage) data;
                boolean required = targetMsg.isFlag();
                // Try to find valid targets from multiple sources
                Set<UUID> targets = findValidTargets(targetMsg);
                if (required && targets != null && !targets.isEmpty()) {
                    UUID firstTarget = selectDeterministicTarget(targets, null);
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

            case GAME_GET_AMOUNT:
                GameClientMessage amountMsg = (GameClientMessage) data;
                int min = amountMsg.getMin();
                session.sendPlayerInteger(gameId, min);
                result.put("action_taken", "selected_min_amount");
                result.put("amount", min);
                break;

            case GAME_GET_MULTI_AMOUNT:
                GameClientMessage multiMsg = (GameClientMessage) data;
                StringBuilder sb = new StringBuilder();
                if (multiMsg.getMessages() != null) {
                    for (int i = 0; i < multiMsg.getMessages().size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(multiMsg.getMessages().get(i).defaultValue);
                    }
                }
                session.sendPlayerString(gameId, sb.toString());
                result.put("action_taken", "selected_default_multi_amount");
                break;

            default:
                result.put("success", false);
                result.put("error", "Unknown action type: " + method);
        }

        return result;
    }

    /**
     * Get structured information about the current pending action's available choices.
     * Returns indexed choices so external clients can pick by index via chooseAction().
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getActionChoices() {
        Map<String, Object> result = new HashMap<>();
        PendingAction action = pendingAction;
        GameView gameView = lastGameView; // snapshot volatile to prevent TOCTOU race

        if (action == null) {
            result.put("action_pending", false);
            return result;
        }

        result.put("action_pending", true);
        result.put("action_type", action.getMethod().name());
        result.put("message", action.getMessage());

        // Add compact phase context and player summary
        if (gameView != null) {
            int turn = roundTracker.update(gameView);
            boolean isMyTurn = client.getUsername().equals(gameView.getActivePlayerName());
            boolean isMainPhase = gameView.getPhase() != null && gameView.getPhase().isMain();

            StringBuilder ctx = new StringBuilder();
            ctx.append("T").append(turn);
            if (gameView.getPhase() != null) {
                ctx.append(" ").append(gameView.getPhase());
            }
            if (gameView.getStep() != null) {
                ctx.append("/").append(gameView.getStep());
            }
            ctx.append(" (").append(gameView.getActivePlayerName()).append(")");
            if (isMyTurn && isMainPhase) {
                ctx.append(" YOUR_MAIN");
            }
            result.put("context", ctx.toString());

            // Compact player summary: "You(40), Opp1(38), Opp2(40)"
            UUID gameId = currentGameId; // snapshot volatile to prevent TOCTOU race
            UUID myPlayerId = gameId != null ? activeGames.get(gameId) : null;
            StringBuilder playerSummary = new StringBuilder();
            for (PlayerView player : gameView.getPlayers()) {
                if (playerSummary.length() > 0) playerSummary.append(", ");
                playerSummary.append(player.getName());
                if (player.getPlayerId().equals(myPlayerId)) {
                    playerSummary.append("(you,");
                } else {
                    playerSummary.append("(");
                }
                playerSummary.append(player.getLife()).append("hp)");
            }
            result.put("players", playerSummary.toString());

            // Add mana pool and untapped land count for the current player
            ManaPoolView pool = getMyManaPoolView(gameView);
            if (pool != null) {
                int total = pool.getRed() + pool.getGreen() + pool.getBlue()
                          + pool.getWhite() + pool.getBlack() + pool.getColorless();
                if (total > 0) {
                    Map<String, Integer> mana = new HashMap<>();
                    if (pool.getRed() > 0) mana.put("R", pool.getRed());
                    if (pool.getGreen() > 0) mana.put("G", pool.getGreen());
                    if (pool.getBlue() > 0) mana.put("U", pool.getBlue());
                    if (pool.getWhite() > 0) mana.put("W", pool.getWhite());
                    if (pool.getBlack() > 0) mana.put("B", pool.getBlack());
                    if (pool.getColorless() > 0) mana.put("C", pool.getColorless());
                    result.put("mana_pool", mana);
                }
            }

            PlayerView myPlayer = gameView.getMyPlayer();
            if (myPlayer != null && myPlayer.getBattlefield() != null) {
                int untappedLands = 0;
                for (PermanentView perm : myPlayer.getBattlefield().values()) {
                    if (perm.isLand() && !perm.isTapped()) {
                        untappedLands++;
                    }
                }
                if (untappedLands > 0) {
                    result.put("untapped_lands", untappedLands);
                }
            }
            // Analogous to Arena highlighting your lands when you have a land drop left.
            // Helps LLMs remember they can play a land this turn.
            if (isMyTurn && isMainPhase) {
                result.put("land_drops_used", landsPlayedThisTurn);
            }
        }

        ClientCallbackMethod method = action.getMethod();
        Object data = action.getData();

        switch (method) {
            case GAME_ASK: {
                result.put("response_type", "boolean");
                lastChoices = null;

                // For mulligan decisions, include hand contents so LLM can evaluate
                String askMsg = action.getMessage();
                if (askMsg != null && askMsg.toLowerCase().contains("mulligan") && gameView != null) {
                    CardsView hand = gameView.getMyHand();
                    if (hand != null && !hand.isEmpty()) {
                        List<Map<String, Object>> handCards = new ArrayList<>();
                        for (CardView card : hand.values()) {
                            Map<String, Object> cardInfo = new HashMap<>();
                            cardInfo.put("name", card.getDisplayName());
                            String manaCost = card.getManaCostStr();
                            if (manaCost != null && !manaCost.isEmpty()) {
                                cardInfo.put("mana_cost", manaCost);
                            }
                            cardInfo.put("mana_value", card.getManaValue());
                            if (card.isLand()) {
                                cardInfo.put("is_land", true);
                            }
                            if (card.isCreature() && card.getPower() != null) {
                                cardInfo.put("power", card.getPower());
                                cardInfo.put("toughness", card.getToughness());
                            }
                            handCards.add(cardInfo);
                        }
                        result.put("your_hand", handCards);
                        // Count lands for quick evaluation
                        int landCount = 0;
                        for (CardView card : hand.values()) {
                            if (card.isLand()) landCount++;
                        }
                        result.put("land_count", landCount);
                        result.put("hand_size", hand.size());
                    }
                }
                break;
            }

            case GAME_SELECT: {
                // Check for playable cards in the current game view
                PlayableObjectsList playable = gameView != null ? gameView.getCanPlayObjects() : null;
                List<Map<String, Object>> choiceList = new ArrayList<>();
                List<Object> indexToUuid = new ArrayList<>();

                if (playable != null && !playable.isEmpty()) {
                    // Clear failed casts and loop counter on turn change
                    if (gameView != null) {
                        int turn = gameView.getTurn();
                        if (turn != lastTurnNumber) {
                            lastTurnNumber = turn;
                            failedManaCasts.clear();
                            interactionsThisTurn = 0;
                            landsPlayedThisTurn = 0;
                        }
                    }

                    int idx = 0;
                    for (Map.Entry<UUID, PlayableObjectStats> entry : playable.getObjects().entrySet()) {
                        UUID objectId = entry.getKey();
                        PlayableObjectStats stats = entry.getValue();

                        // Skip spells that failed mana payment (can't afford them)
                        if (failedManaCasts.contains(objectId)) {
                            continue;
                        }

                        // Skip objects whose only abilities are mana abilities
                        // (mana payment is handled during GAME_PLAY_MANA, not GAME_SELECT)
                        List<String> abilityNames = stats.getPlayableAbilityNames();
                        List<String> manaNames = stats.getAllManaAbilityNames();
                        if (!abilityNames.isEmpty() && manaNames.size() == abilityNames.size()) {
                            continue;
                        }

                        Map<String, Object> choiceEntry = new HashMap<>();
                        choiceEntry.put("index", idx);

                        // Determine where this object lives (hand = cast, battlefield = activate)
                        CardView cardView = findCardViewById(objectId);
                        boolean isOnBattlefield = false;
                        if (cardView == null) {
                            // not found in hand/stack, check battlefield directly
                            isOnBattlefield = true;
                        } else if (gameView.getMyHand().get(objectId) == null
                                   && gameView.getStack().get(objectId) == null) {
                            isOnBattlefield = true;
                        }

                        if (cardView != null) {
                            StringBuilder desc = new StringBuilder();
                            if (isOnBattlefield) {
                                // Show as activated ability, not a card to cast
                                // Filter out mana abilities from the description
                                Set<String> manaNameSet = new HashSet<>(stats.getAllManaAbilityNames());
                                List<String> nonManaAbilities = new ArrayList<>();
                                for (String name : abilityNames) {
                                    if (!manaNameSet.contains(name)) {
                                        nonManaAbilities.add(name);
                                    }
                                }
                                desc.append(cardView.getDisplayName());
                                if (!nonManaAbilities.isEmpty()) {
                                    desc.append(" — ").append(String.join("; ", nonManaAbilities));
                                }
                                desc.append(" [Activate]");
                            } else {
                                desc.append(cardView.getDisplayName());
                                String manaCost = cardView.getManaCostStr();
                                if (manaCost != null && !manaCost.isEmpty()) {
                                    desc.append(" ").append(manaCost);
                                }
                                if (cardView.isCreature() && cardView.getPower() != null) {
                                    desc.append(" ").append(cardView.getPower()).append("/").append(cardView.getToughness());
                                }
                                if (cardView.isLand()) {
                                    desc.append(" [Land]");
                                } else if (cardView.isCreature()) {
                                    desc.append(" [Creature]");
                                } else {
                                    desc.append(" [Cast]");
                                }
                            }
                            choiceEntry.put("description", desc.toString());
                        } else {
                            choiceEntry.put("description", "Unknown (" + objectId.toString().substring(0, 8) + ")");
                        }

                        choiceList.add(choiceEntry);
                        indexToUuid.add(objectId);
                        idx++;
                    }
                }

                // Check for combat selections (declare attackers / declare blockers)
                if (data instanceof GameClientMessage) {
                    GameClientMessage gcm = (GameClientMessage) data;
                    Map<String, Serializable> options = gcm.getOptions();
                    if (options != null) {
                        @SuppressWarnings("unchecked")
                        List<UUID> possibleAttackerIds = (List<UUID>) options.get("possibleAttackers");
                        @SuppressWarnings("unchecked")
                        List<UUID> possibleBlockerIds = (List<UUID>) options.get("possibleBlockers");

                        if (possibleAttackerIds != null && !possibleAttackerIds.isEmpty()) {
                            result.put("combat_phase", "declare_attackers");

                            // Show which creatures are already attacking
                            List<String> alreadyAttacking = new ArrayList<>();
                            if (gameView != null && gameView.getCombat() != null) {
                                for (CombatGroupView group : gameView.getCombat()) {
                                    for (CardView attacker : group.getAttackers().values()) {
                                        StringBuilder desc = new StringBuilder();
                                        desc.append(attacker.getDisplayName());
                                        if (attacker.getPower() != null) {
                                            desc.append(" ").append(attacker.getPower())
                                                .append("/").append(attacker.getToughness());
                                        }
                                        alreadyAttacking.add(desc.toString());
                                    }
                                }
                            }
                            if (!alreadyAttacking.isEmpty()) {
                                result.put("already_attacking", alreadyAttacking);
                            }

                            int idx = choiceList.size();
                            for (UUID attackerId : possibleAttackerIds) {
                                PermanentView perm = findPermanentViewById(attackerId, gameView);
                                if (perm == null) continue;

                                Map<String, Object> choiceEntry = new HashMap<>();
                                choiceEntry.put("index", idx);
                                StringBuilder desc = new StringBuilder();
                                desc.append(perm.getDisplayName());
                                if (perm.getPower() != null) {
                                    desc.append(" ").append(perm.getPower())
                                        .append("/").append(perm.getToughness());
                                }
                                desc.append(" [Attack]");
                                choiceEntry.put("description", desc.toString());
                                choiceEntry.put("choice_type", "attacker");
                                choiceList.add(choiceEntry);
                                indexToUuid.add(attackerId);
                                idx++;
                            }

                            // Add "All attack" special option if available
                            if (options.containsKey("specialButton")) {
                                Map<String, Object> allAttackEntry = new HashMap<>();
                                allAttackEntry.put("index", idx);
                                allAttackEntry.put("description", "All attack");
                                allAttackEntry.put("choice_type", "special");
                                choiceList.add(allAttackEntry);
                                indexToUuid.add("special");
                                idx++;
                            }
                        }

                        if (possibleBlockerIds != null && !possibleBlockerIds.isEmpty()) {
                            result.put("combat_phase", "declare_blockers");

                            // Show attacking creatures for context
                            List<Map<String, Object>> incomingAttackers = new ArrayList<>();
                            if (gameView != null && gameView.getCombat() != null) {
                                for (CombatGroupView group : gameView.getCombat()) {
                                    for (CardView attacker : group.getAttackers().values()) {
                                        Map<String, Object> aInfo = new HashMap<>();
                                        aInfo.put("name", attacker.getDisplayName());
                                        if (attacker.getPower() != null) {
                                            aInfo.put("power", attacker.getPower());
                                            aInfo.put("toughness", attacker.getToughness());
                                        }
                                        incomingAttackers.add(aInfo);
                                    }
                                }
                            }
                            if (!incomingAttackers.isEmpty()) {
                                result.put("incoming_attackers", incomingAttackers);
                            }

                            int idx = choiceList.size();
                            for (UUID blockerId : possibleBlockerIds) {
                                PermanentView perm = findPermanentViewById(blockerId, gameView);
                                if (perm == null) continue;

                                Map<String, Object> choiceEntry = new HashMap<>();
                                choiceEntry.put("index", idx);
                                StringBuilder desc = new StringBuilder();
                                desc.append(perm.getDisplayName());
                                if (perm.getPower() != null) {
                                    desc.append(" ").append(perm.getPower())
                                        .append("/").append(perm.getToughness());
                                }
                                desc.append(" [Block]");
                                choiceEntry.put("description", desc.toString());
                                choiceEntry.put("choice_type", "blocker");
                                choiceList.add(choiceEntry);
                                indexToUuid.add(blockerId);
                                idx++;
                            }
                        }
                    }
                }

                if (!choiceList.isEmpty()) {
                    result.put("response_type", "select");
                    result.put("choices", choiceList);
                    lastChoices = indexToUuid;
                } else {
                    result.put("response_type", "boolean");
                    lastChoices = null;
                }
                break;
            }

            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA: {
                // Auto-tap couldn't find a source — show available mana sources to the LLM
                GameClientMessage manaMsg = (GameClientMessage) data;
                result.put("response_type", "select");

                PlayableObjectsList manaPlayable = gameView != null ? gameView.getCanPlayObjects() : null;
                List<Map<String, Object>> manaChoiceList = new ArrayList<>();
                List<Object> manaIndexToChoice = new ArrayList<>();
                UUID payingForId = extractPayingForId(manaMsg.getMessage());

                if (manaPlayable != null) {
                    int idx = 0;
                    for (Map.Entry<UUID, PlayableObjectStats> entry : manaPlayable.getObjects().entrySet()) {
                        UUID manaObjectId = entry.getKey();
                        if (manaObjectId.equals(payingForId)) {
                            continue;
                        }
                        PlayableObjectStats stats = entry.getValue();
                        List<String> manaAbilities = stats.getAllManaAbilityNames();
                        if (manaAbilities.isEmpty()) {
                            continue;
                        }

                        CardView cardView = findCardViewById(manaObjectId);
                        String cardName;
                        if (cardView != null) {
                            cardName = cardView.getDisplayName();
                        } else {
                            cardName = "Unknown (" + manaObjectId.toString().substring(0, 8) + ")";
                        }

                        for (String manaAbilityText : manaAbilities) {
                            Map<String, Object> choiceEntry = new HashMap<>();
                            choiceEntry.put("index", idx);
                            boolean isTap = manaAbilityText.contains("{T}");
                            choiceEntry.put("choice_type", isTap ? "tap_source" : "mana_source");
                            choiceEntry.put("description", cardName + " — " + manaAbilityText);
                            manaChoiceList.add(choiceEntry);
                            manaIndexToChoice.add(manaObjectId);
                            idx++;
                        }
                    }
                }

                List<ManaType> poolChoices = getPoolManaChoices(gameView, manaMsg.getMessage());
                if (!poolChoices.isEmpty()) {
                    int idx = manaChoiceList.size();
                    ManaPoolView manaPool = getMyManaPoolView(gameView);
                    for (ManaType manaType : poolChoices) {
                        Map<String, Object> choiceEntry = new HashMap<>();
                        choiceEntry.put("index", idx);
                        choiceEntry.put("choice_type", "pool_mana");
                        choiceEntry.put("description", "Mana Pool — " + prettyManaType(manaType) + " (" + getManaPoolCount(manaPool, manaType) + ")");
                        manaChoiceList.add(choiceEntry);
                        manaIndexToChoice.add(manaType);
                        idx++;
                    }
                }

                result.put("choices", manaChoiceList);
                lastChoices = manaIndexToChoice;
                break;
            }

            case GAME_TARGET: {
                GameClientMessage msg = (GameClientMessage) data;
                result.put("response_type", "index");
                boolean required = msg.isFlag();
                result.put("required", required);
                result.put("can_cancel", !required);

                Set<UUID> targets = findValidTargets(msg);
                List<Map<String, Object>> choiceList = new ArrayList<>();
                List<Object> indexToUuid = new ArrayList<>();

                if (targets != null) {
                    CardsView cardsView = msg.getCardsView1();
                    int idx = 0;
                    for (UUID targetId : targets) {
                        Map<String, Object> choiceEntry = new HashMap<>();
                        choiceEntry.put("index", idx);
                        choiceEntry.put("description", describeTarget(targetId, cardsView, msg.getGameView()));
                        choiceList.add(choiceEntry);
                        indexToUuid.add(targetId);
                        idx++;
                    }
                }

                result.put("choices", choiceList);
                lastChoices = indexToUuid;
                break;
            }

            case GAME_CHOOSE_ABILITY: {
                AbilityPickerView picker = (AbilityPickerView) data;
                Map<UUID, String> choices = picker.getChoices();
                result.put("response_type", "index");

                List<Map<String, Object>> choiceList = new ArrayList<>();
                List<Object> indexToUuid = new ArrayList<>();

                if (choices != null) {
                    int idx = 0;
                    for (Map.Entry<UUID, String> entry : choices.entrySet()) {
                        Map<String, Object> choiceEntry = new HashMap<>();
                        choiceEntry.put("index", idx);
                        choiceEntry.put("description", entry.getValue());
                        choiceList.add(choiceEntry);
                        indexToUuid.add(entry.getKey());
                        idx++;
                    }
                }

                result.put("choices", choiceList);
                lastChoices = indexToUuid;
                break;
            }

            case GAME_CHOOSE_CHOICE: {
                GameClientMessage msg = (GameClientMessage) data;
                Choice choice = msg.getChoice();
                result.put("response_type", "index");

                List<Map<String, Object>> choiceList = new ArrayList<>();
                List<Object> indexToKey = new ArrayList<>();

                if (choice != null) {
                    if (choice.isKeyChoice()) {
                        Map<String, String> keyChoices = choice.getKeyChoices();
                        if (keyChoices != null) {
                            int idx = 0;
                            for (Map.Entry<String, String> entry : keyChoices.entrySet()) {
                                Map<String, Object> choiceEntry = new HashMap<>();
                                choiceEntry.put("index", idx);
                                choiceEntry.put("description", entry.getValue());
                                choiceList.add(choiceEntry);
                                indexToKey.add(entry.getKey());
                                idx++;
                            }
                        }
                    } else {
                        Set<String> choices = choice.getChoices();
                        if (choices != null) {
                            int idx = 0;
                            for (String c : choices) {
                                Map<String, Object> choiceEntry = new HashMap<>();
                                choiceEntry.put("index", idx);
                                choiceEntry.put("description", c);
                                choiceList.add(choiceEntry);
                                indexToKey.add(c);
                                idx++;
                            }
                        }
                    }
                }

                // Filter large choice lists to deck-relevant options
                int totalChoices = choiceList.size();
                if (totalChoices >= 50 && deckList != null) {
                    Set<String> deckTypes = getDeckCreatureTypes();
                    if (!deckTypes.isEmpty()) {
                        List<Map<String, Object>> filtered = new ArrayList<>();
                        List<Object> filteredKeys = new ArrayList<>();
                        int idx = 0;
                        for (int i = 0; i < choiceList.size(); i++) {
                            String desc = (String) choiceList.get(i).get("description");
                            if (deckTypes.contains(desc)) {
                                Map<String, Object> entry = new HashMap<>();
                                entry.put("index", idx);
                                entry.put("description", desc);
                                filtered.add(entry);
                                filteredKeys.add(indexToKey.get(i));
                                idx++;
                            }
                        }
                        if (!filtered.isEmpty()) {
                            choiceList = filtered;
                            indexToKey = filteredKeys;
                            result.put("note", "Showing " + filtered.size()
                                + " types from your deck (" + totalChoices
                                + " total available). Use choose_action(text='TypeName') for any other type.");
                        }
                    }
                }

                result.put("choices", choiceList);
                lastChoices = indexToKey;
                break;
            }

            case GAME_CHOOSE_PILE: {
                GameClientMessage msg = (GameClientMessage) data;
                result.put("response_type", "pile");

                List<String> pile1 = new ArrayList<>();
                List<String> pile2 = new ArrayList<>();
                if (msg.getCardsView1() != null) {
                    for (CardView card : msg.getCardsView1().values()) {
                        pile1.add(card.getDisplayName());
                    }
                }
                if (msg.getCardsView2() != null) {
                    for (CardView card : msg.getCardsView2().values()) {
                        pile2.add(card.getDisplayName());
                    }
                }
                result.put("pile1", pile1);
                result.put("pile2", pile2);
                lastChoices = null;
                break;
            }

            case GAME_GET_AMOUNT: {
                GameClientMessage msg = (GameClientMessage) data;
                result.put("response_type", "amount");
                result.put("min", msg.getMin());
                result.put("max", msg.getMax());
                lastChoices = null;
                break;
            }

            case GAME_GET_MULTI_AMOUNT: {
                GameClientMessage msg = (GameClientMessage) data;
                result.put("response_type", "multi_amount");
                result.put("total_min", msg.getMin());
                result.put("total_max", msg.getMax());

                List<Map<String, Object>> items = new ArrayList<>();
                if (msg.getMessages() != null) {
                    for (MultiAmountMessage mam : msg.getMessages()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("description", mam.message);
                        item.put("min", mam.min);
                        item.put("max", mam.max);
                        item.put("default", mam.defaultValue);
                        items.add(item);
                    }
                }
                result.put("items", items);
                lastChoices = null;
                break;
            }

            default:
                result.put("response_type", "unknown");
                result.put("error", "Unhandled action type: " + method);
                lastChoices = null;
        }

        return result;
    }

    /**
     * Respond to the current pending action with a specific choice.
     * Exactly one parameter should be non-null, matching the response_type from getActionChoices().
     */
    public Map<String, Object> chooseAction(Integer index, Boolean answer, Integer amount, int[] amounts, Integer pile, String text) {
        interactionsThisTurn++;
        Map<String, Object> result = new HashMap<>();
        PendingAction action = pendingAction;

        if (action == null) {
            result.put("success", false);
            result.put("error", "No pending action");
            return result;
        }

        // Loop detection: model has made too many interactions this turn — auto-handle
        if (interactionsThisTurn > maxInteractionsPerTurn) {
            logger.warn("[" + client.getUsername() + "] Loop detected (" + interactionsThisTurn
                + " interactions this turn), auto-handling " + action.getMethod().name());
            executeDefaultAction();
            result.put("success", true);
            result.put("action_taken", "auto_passed_loop_detected");
            result.put("warning", "Too many interactions this turn (" + interactionsThisTurn + "). Auto-passing until next turn.");
            return result;
        }

        // Clear pending action only if it hasn't been overwritten by a new callback.
        // Without this CAS, a callback arriving between our read and this write would be lost.
        synchronized (actionLock) {
            if (pendingAction == action) {
                pendingAction = null;
            }
        }

        UUID gameId = action.getGameId();
        ClientCallbackMethod method = action.getMethod();
        Object data = action.getData();

        // Auto-populate choices if the model skipped get_action_choices.
        // Some models send all params with defaults (e.g. index=0, answer=false);
        // we still need choices populated so the index path can work.
        if (index != null && lastChoices == null) {
            logger.info("[" + client.getUsername() + "] choose_action: auto-populating choices (get_action_choices was not called)");
            getActionChoices();
        }

        result.put("success", true);

        try {
            switch (method) {
                case GAME_ASK:
                    // GAME_ASK is boolean-only; ignore index if also provided
                    // (some models send all params with defaults)
                    if (answer == null) {
                        result.put("success", false);
                        result.put("error", "Boolean 'answer' required for " + method);
                        pendingAction = action;
                        return result;
                    }
                    if (index != null) {
                        logger.warn("[" + client.getUsername() + "] choose_action: ignoring index=" + index + " for GAME_ASK (boolean-only)");
                    }
                    session.sendPlayerBoolean(gameId, answer);
                    trackSentResponse(gameId, ResponseType.BOOLEAN, answer, null);
                    result.put("action_taken", answer ? "yes" : "no");
                    break;

                case GAME_SELECT: {
                    // Support both index (play a card) and answer (pass priority).
                    // When both are provided (some models send all params with defaults),
                    // try index first but fall through to answer if index is invalid.
                    boolean usedIndex = false;
                    if (index != null) {
                        List<Object> choices = lastChoices; // snapshot volatile to prevent TOCTOU race
                        if (choices == null || index < 0 || index >= choices.size()) {
                            // Index is invalid — if answer is also available, fall through
                            if (answer != null) {
                                logger.warn("[" + client.getUsername() + "] choose_action: index " + index
                                    + " out of range, falling through to answer=" + answer + " for GAME_SELECT");
                            } else {
                                result.put("success", false);
                                result.put("error", "Index " + index + " out of range (call get_action_choices first)");
                                pendingAction = action;
                                return result;
                            }
                        } else {
                            Object chosen = choices.get(index);
                            if (chosen instanceof UUID) {
                                session.sendPlayerUUID(gameId, (UUID) chosen);
                                trackSentResponse(gameId, ResponseType.UUID, chosen, null);
                                result.put("action_taken", "selected_" + index);
                                usedIndex = true;
                            } else if (chosen instanceof String) {
                                session.sendPlayerString(gameId, (String) chosen);
                                trackSentResponse(gameId, ResponseType.STRING, chosen, null);
                                result.put("action_taken", "special_" + chosen);
                                usedIndex = true;
                            } else {
                                result.put("success", false);
                                result.put("error", "Unexpected choice type at index " + index);
                                pendingAction = action;
                                return result;
                            }
                        }
                    }
                    if (!usedIndex) {
                        if (answer != null) {
                            session.sendPlayerBoolean(gameId, answer);
                            result.put("action_taken", answer ? "confirmed" : "passed_priority");
                        } else {
                            result.put("success", false);
                            result.put("error", "Provide 'index' to play a card or 'answer: false' to pass priority");
                            pendingAction = action;
                            return result;
                        }
                    }
                    break;
                }

                case GAME_PLAY_MANA:
                case GAME_PLAY_XMANA: {
                    // index = tap a mana source OR spend a mana type from pool, answer=false = cancel.
                    // When both are provided and index is invalid, fall through to answer.
                    boolean usedManaIndex = false;
                    if (index != null) {
                        List<Object> choices = lastChoices; // snapshot volatile to prevent TOCTOU race
                        if (choices == null || index < 0 || index >= choices.size()) {
                            if (answer != null && !answer) {
                                logger.warn("[" + client.getUsername() + "] choose_action: index " + index
                                    + " out of range, falling through to cancel for GAME_PLAY_MANA");
                            } else {
                                result.put("success", false);
                                result.put("error", "Index " + index + " out of range (call get_action_choices first)");
                                pendingAction = action;
                                return result;
                            }
                        } else {
                            Object manaChoice = choices.get(index);
                            if (manaChoice instanceof UUID) {
                                session.sendPlayerUUID(gameId, (UUID) manaChoice);
                                trackSentResponse(gameId, ResponseType.UUID, manaChoice, null);
                                result.put("action_taken", "tapped_mana_" + index);
                                usedManaIndex = true;
                            } else if (manaChoice instanceof ManaType) {
                                UUID manaPlayerId = getManaPoolPlayerId(gameId, lastGameView);
                                if (manaPlayerId == null) {
                                    result.put("success", false);
                                    result.put("error", "Could not resolve player ID for mana pool selection");
                                    pendingAction = action;
                                    return result;
                                }
                                ManaType manaType = (ManaType) manaChoice;
                                session.sendPlayerManaType(gameId, manaPlayerId, manaType);
                                trackSentResponse(gameId, ResponseType.MANA_TYPE, manaType, manaPlayerId);
                                result.put("action_taken", "used_pool_" + manaType.toString());
                                usedManaIndex = true;
                            } else {
                                result.put("success", false);
                                result.put("error", "Unsupported mana choice type at index " + index);
                                pendingAction = action;
                                return result;
                            }
                        }
                    }
                    if (!usedManaIndex) {
                        if (answer != null && !answer) {
                            // Mark spell as failed to prevent infinite retry loop
                            UUID payingForId = extractPayingForId(action.getMessage());
                            if (payingForId != null) {
                                failedManaCasts.add(payingForId);
                            }
                            session.sendPlayerBoolean(gameId, false);
                            result.put("action_taken", "cancelled_spell");
                        } else {
                            result.put("success", false);
                            result.put("error", "Provide 'index' to choose mana source/pool, or 'answer: false' to cancel");
                            pendingAction = action;
                            return result;
                        }
                    }
                    break;
                }

                case GAME_TARGET: {
                    GameClientMessage targetMsg = (GameClientMessage) data;
                    boolean required = targetMsg.isFlag();

                    // Index takes priority over answer:false (models sometimes send both)
                    if (index != null) {
                        if (answer != null) {
                            logger.warn("[" + client.getUsername() + "] choose_action: ignoring answer=" + answer + " because index was also provided for GAME_TARGET");
                        }
                        List<Object> choices = lastChoices; // snapshot volatile to prevent TOCTOU race
                        if (choices != null && index >= 0 && index < choices.size()) {
                            UUID targetUUID = (UUID) choices.get(index);
                            session.sendPlayerUUID(gameId, targetUUID);
                            trackSentResponse(gameId, ResponseType.UUID, targetUUID, null);
                            result.put("action_taken", "selected_target_" + index);
                            break;
                        }
                        // Index out of range. For required targets, auto-select to avoid
                        // infinite retry loops. For optional targets, return an error so
                        // the model can retry with a valid index or answer=false.
                        if (!required) {
                            result.put("success", false);
                            result.put("error", "Index " + index + " out of range (call get_action_choices first)");
                            pendingAction = action;
                            return result;
                        }
                        logger.warn("[" + client.getUsername() + "] choose_action: index " + index
                            + " out of range for required GAME_TARGET (choices="
                            + (choices == null ? "null" : choices.size()) + "), auto-selecting");
                    } else if (answer != null && !answer) {
                        // Explicit cancel via answer=false
                        if (!required) {
                            session.sendPlayerBoolean(gameId, false);
                            trackSentResponse(gameId, ResponseType.BOOLEAN, false, null);
                            result.put("action_taken", "cancelled");
                            break;
                        }
                        // Required target — can't cancel, fall through to auto-select
                        logger.warn("[" + client.getUsername() + "] choose_action: answer=false invalid for required GAME_TARGET, auto-selecting");
                    } else if (!required) {
                        // No index, no answer=false — return error for optional targets
                        result.put("success", false);
                        result.put("error", "Integer 'index' required for GAME_TARGET (or answer=false to cancel)");
                        pendingAction = action;
                        return result;
                    }

                    // Auto-select for required targets when index was invalid/missing
                    Set<UUID> autoTargets = findValidTargets(targetMsg);
                    if (autoTargets != null && !autoTargets.isEmpty()) {
                        UUID firstTarget = selectDeterministicTarget(autoTargets, lastChoices);
                        logger.warn("[" + client.getUsername() + "] choose_action: auto-selecting first target for required GAME_TARGET");
                        session.sendPlayerUUID(gameId, firstTarget);
                        trackSentResponse(gameId, ResponseType.UUID, firstTarget, null);
                        result.put("action_taken", "auto_selected_required_target");
                        result.put("warning", "Required target auto-selected. Use get_action_choices first, then index=N.");
                    } else {
                        logger.error("[" + client.getUsername() + "] Required GAME_TARGET has no valid targets — cancelling to avoid infinite loop");
                        session.sendPlayerBoolean(gameId, false);
                        trackSentResponse(gameId, ResponseType.BOOLEAN, false, null);
                        result.put("action_taken", "cancelled_no_valid_targets");
                    }
                    break;
                }

                case GAME_CHOOSE_ABILITY: {
                    if (index == null) {
                        result.put("success", false);
                        result.put("error", "Integer 'index' required for GAME_CHOOSE_ABILITY");
                        pendingAction = action;
                        return result;
                    }
                    List<Object> abilityChoices = lastChoices; // snapshot volatile to prevent TOCTOU race
                    if (abilityChoices == null || index < 0 || index >= abilityChoices.size()) {
                        result.put("success", false);
                        result.put("error", "Index " + index + " out of range (call get_action_choices first)");
                        pendingAction = action;
                        return result;
                    }
                    UUID abilityUUID = (UUID) abilityChoices.get(index);
                    session.sendPlayerUUID(gameId, abilityUUID);
                    trackSentResponse(gameId, ResponseType.UUID, abilityUUID, null);
                    result.put("action_taken", "selected_ability_" + index);
                    break;
                }

                case GAME_CHOOSE_CHOICE: {
                    // Support text parameter for choosing by name (e.g. creature type not in filtered list)
                    if (text != null && !text.isEmpty()) {
                        GameClientMessage choiceMsg = (GameClientMessage) data;
                        Choice choiceObj = choiceMsg.getChoice();
                        if (choiceObj == null) {
                            result.put("success", false);
                            result.put("error", "No choice available");
                            pendingAction = action;
                            return result;
                        }
                        // Validate text is a legal choice
                        if (choiceObj.isKeyChoice()) {
                            // For key choices, text must match a value; find the key
                            Map<String, String> keyChoices = choiceObj.getKeyChoices();
                            String matchedKey = null;
                            if (keyChoices != null) {
                                for (Map.Entry<String, String> entry : keyChoices.entrySet()) {
                                    if (entry.getValue().equalsIgnoreCase(text) || entry.getKey().equalsIgnoreCase(text)) {
                                        matchedKey = entry.getKey();
                                        break;
                                    }
                                }
                            }
                            if (matchedKey == null) {
                                result.put("success", false);
                                result.put("error", "'" + text + "' is not a valid choice");
                                pendingAction = action;
                                return result;
                            }
                            session.sendPlayerString(gameId, matchedKey);
                            trackSentResponse(gameId, ResponseType.STRING, matchedKey, null);
                        } else {
                            // For plain choices, text must match a choice string
                            Set<String> choices = choiceObj.getChoices();
                            String matched = null;
                            if (choices != null) {
                                for (String c : choices) {
                                    if (c.equalsIgnoreCase(text)) {
                                        matched = c;
                                        break;
                                    }
                                }
                            }
                            if (matched == null) {
                                result.put("success", false);
                                result.put("error", "'" + text + "' is not a valid choice");
                                pendingAction = action;
                                return result;
                            }
                            session.sendPlayerString(gameId, matched);
                            trackSentResponse(gameId, ResponseType.STRING, matched, null);
                        }
                        result.put("action_taken", "selected_choice_text_" + text);
                        break;
                    }
                    if (index == null) {
                        result.put("success", false);
                        result.put("error", "Integer 'index' or string 'text' required for GAME_CHOOSE_CHOICE");
                        pendingAction = action;
                        return result;
                    }
                    List<Object> choiceChoices = lastChoices; // snapshot volatile to prevent TOCTOU race
                    if (choiceChoices == null || index < 0 || index >= choiceChoices.size()) {
                        result.put("success", false);
                        result.put("error", "Index " + index + " out of range (call get_action_choices first)");
                        pendingAction = action;
                        return result;
                    }
                    String choiceStr = (String) choiceChoices.get(index);
                    session.sendPlayerString(gameId, choiceStr);
                    trackSentResponse(gameId, ResponseType.STRING, choiceStr, null);
                    result.put("action_taken", "selected_choice_" + index);
                    break;
                }

                case GAME_CHOOSE_PILE:
                    if (pile == null) {
                        result.put("success", false);
                        result.put("error", "Integer 'pile' (1 or 2) required for GAME_CHOOSE_PILE");
                        pendingAction = action;
                        return result;
                    }
                    boolean pileChoice = pile == 1;
                    session.sendPlayerBoolean(gameId, pileChoice);
                    trackSentResponse(gameId, ResponseType.BOOLEAN, pileChoice, null);
                    result.put("action_taken", "selected_pile_" + pile);
                    break;

                case GAME_GET_AMOUNT: {
                    if (amount == null) {
                        result.put("success", false);
                        result.put("error", "Integer 'amount' required for GAME_GET_AMOUNT");
                        pendingAction = action;
                        return result;
                    }
                    GameClientMessage msg = (GameClientMessage) data;
                    int clamped = Math.max(msg.getMin(), Math.min(msg.getMax(), amount));
                    session.sendPlayerInteger(gameId, clamped);
                    trackSentResponse(gameId, ResponseType.INTEGER, clamped, null);
                    result.put("action_taken", "amount_" + clamped);
                    break;
                }

                case GAME_GET_MULTI_AMOUNT: {
                    if (amounts == null) {
                        result.put("success", false);
                        result.put("error", "Array 'amounts' required for GAME_GET_MULTI_AMOUNT");
                        pendingAction = action;
                        return result;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < amounts.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(amounts[i]);
                    }
                    String multiAmountStr = sb.toString();
                    session.sendPlayerString(gameId, multiAmountStr);
                    trackSentResponse(gameId, ResponseType.STRING, multiAmountStr, null);
                    result.put("action_taken", "multi_amount");
                    break;
                }

                default:
                    result.put("success", false);
                    result.put("error", "Unknown action type: " + method);
            }
        } finally {
            lastChoices = null;
            if (Boolean.FALSE.equals(result.get("success"))) {
                logError("choose_action failed: " + result.get("error"));
            }
        }

        return result;
    }

    private String describeTarget(UUID targetId, CardsView cardsView, GameView gameView) {
        GameView view = gameView != null ? gameView : lastGameView;
        // Try cardsView first (cards presented in the targeting UI)
        if (cardsView != null) {
            CardView cv = cardsView.get(targetId);
            if (cv != null) {
                return buildCardDescription(cv) + controllerSuffix(targetId, view);
            }
        }
        // Fall back to game state lookup
        CardView cv = findCardViewById(targetId, view);
        if (cv != null) {
            return buildCardDescription(cv) + controllerSuffix(targetId, view);
        }
        // Check if the target is a player
        if (view != null) {
            UUID gameId = currentGameId; // snapshot volatile to prevent TOCTOU race
            UUID myPlayerId = gameId != null ? activeGames.get(gameId) : null;
            for (PlayerView player : view.getPlayers()) {
                if (player.getPlayerId().equals(targetId)) {
                    String desc = player.getName();
                    if (player.getPlayerId().equals(myPlayerId)) {
                        desc += " (you)";
                    }
                    return desc;
                }
            }
        }
        return "Unknown (" + targetId.toString().substring(0, 8) + ")";
    }

    /**
     * Return a suffix like " (yours)" or " (PlayerName's)" indicating who controls
     * the permanent with the given ID. Returns "" if not found on any battlefield.
     */
    private String controllerSuffix(UUID objectId) {
        return controllerSuffix(objectId, lastGameView);
    }

    private String controllerSuffix(UUID objectId, GameView gameView) {
        if (gameView == null) return "";
        UUID gameId = currentGameId;
        UUID myPlayerId = gameId != null ? activeGames.get(gameId) : null;
        for (PlayerView player : gameView.getPlayers()) {
            if (player.getBattlefield().get(objectId) != null) {
                if (player.getPlayerId().equals(myPlayerId)) {
                    return " (yours)";
                } else {
                    return " (" + player.getName() + "'s)";
                }
            }
        }
        return "";
    }

    private String buildCardDescription(CardView cv) {
        String displayName = cv.getDisplayName();
        if (displayName == null) {
            displayName = cv.getName() != null ? cv.getName() : "Unknown";
        }
        StringBuilder sb = new StringBuilder(displayName);
        if (cv instanceof PermanentView) {
            PermanentView pv = (PermanentView) cv;
            if (pv.isCreature() && cv.getPower() != null && cv.getToughness() != null) {
                sb.append(" (").append(cv.getPower()).append("/").append(cv.getToughness()).append(")");
            }
            if (pv.isTapped()) {
                sb.append(" [tapped]");
            }
        }
        return sb.toString();
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
            return gameLog.length() + gameLogTrimmedChars;
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
        // Suppress duplicate messages within the dedup window
        long now = System.currentTimeMillis();
        if (message.equals(lastChatMessage) && (now - lastChatTimeMs) < CHAT_DEDUP_WINDOW_MS) {
            logger.info("[" + client.getUsername() + "] Suppressing duplicate chat message");
            return true; // Pretend success so the model doesn't retry
        }
        lastChatMessage = message;
        lastChatTimeMs = now;
        return session.sendChatMessage(chatId, message);
    }

    public Map<String, Object> waitForAction(int timeoutMs) {
        synchronized (actionLock) {
            if (pendingAction == null) {
                try {
                    actionLock.wait(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return getPendingActionInfo();
    }

    /**
     * Drain unseen chat messages and attach to result map (if any).
     */
    private void attachUnseenChat(Map<String, Object> result) {
        if (playerDead) {
            result.put("player_dead", true);
        }
        synchronized (unseenChat) {
            if (!unseenChat.isEmpty()) {
                result.put("recent_chat", new ArrayList<>(unseenChat));
                unseenChat.clear();
            }
        }
    }

    /**
     * Auto-pass empty GAME_SELECT priorities (no playable cards) and return
     * when a meaningful decision is needed: playable cards available, or
     * any non-GAME_SELECT action (mulligan, target, blocker, etc.).
     */
    public Map<String, Object> passPriority(int timeoutMs) {
        interactionsThisTurn++;
        long startTime = System.currentTimeMillis();
        int actionsPassed = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            PendingAction action = pendingAction;
            if (action != null) {
                ClientCallbackMethod method = action.getMethod();

                // Update game view and reset loop counter on turn change.
                // This MUST run before the loop detection check below, otherwise
                // the `continue` in the loop detection branch skips it and the
                // counter never resets, permanently disabling the player.
                // Check any callback carrying GameView, not just GAME_SELECT —
                // a new turn can start with upkeep triggers (GAME_TARGET, GAME_ASK, etc.).
                if (action.getData() instanceof GameClientMessage) {
                    GameView gv = ((GameClientMessage) action.getData()).getGameView();
                    if (gv != null) {
                        lastGameView = gv;
                        int turn = gv.getTurn();
                        if (turn != lastTurnNumber) {
                            lastTurnNumber = turn;
                            failedManaCasts.clear();
                            interactionsThisTurn = 0;
                            landsPlayedThisTurn = 0;
                        }
                    }
                }

                // Generic loop detection: too many interactions this turn — auto-pass everything
                if (interactionsThisTurn > maxInteractionsPerTurn) {
                    logger.warn("[" + client.getUsername() + "] Loop detected (" + interactionsThisTurn
                        + " interactions on turn " + lastTurnNumber + "), auto-passing " + method.name());
                    executeDefaultAction();
                    actionsPassed++;
                    continue;
                }

                // GAME_PLAY_MANA: auto-tapper couldn't handle it, cancel the spell
                if (method == ClientCallbackMethod.GAME_PLAY_MANA || method == ClientCallbackMethod.GAME_PLAY_XMANA) {
                    UUID payingForId = extractPayingForId(action.getMessage());
                    if (payingForId != null) {
                        failedManaCasts.add(payingForId);
                    }
                    synchronized (actionLock) {
                        if (pendingAction == action) {
                            pendingAction = null;
                        }
                    }
                    session.sendPlayerBoolean(action.getGameId(), false);
                    actionsPassed++;
                    continue;
                }

                // Non-GAME_SELECT always needs LLM input — return immediately
                if (method != ClientCallbackMethod.GAME_SELECT) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("action_pending", true);
                    result.put("action_type", method.name());
                    result.put("actions_passed", actionsPassed);
                    attachUnseenChat(result);
                    return result;
                }

                // Combat selections (declare attackers/blockers) always need LLM input
                String combatType = detectCombatSelect(action);
                if (combatType != null) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("action_pending", true);
                    result.put("action_type", method.name());
                    result.put("actions_passed", actionsPassed);
                    result.put("combat_phase", combatType);
                    attachUnseenChat(result);
                    return result;
                }

                // Check if there are playable cards (non-mana-only, excluding failed casts)
                PlayableObjectsList playable = lastGameView != null ? lastGameView.getCanPlayObjects() : null;
                boolean hasPlayableCards = false;
                if (playable != null && !playable.isEmpty()) {
                    for (Map.Entry<UUID, PlayableObjectStats> entry : playable.getObjects().entrySet()) {
                        if (failedManaCasts.contains(entry.getKey())) {
                            continue;
                        }
                        PlayableObjectStats stats = entry.getValue();
                        List<String> abilityNames = stats.getPlayableAbilityNames();
                        List<String> manaNames = stats.getAllManaAbilityNames();
                        boolean allMana = !abilityNames.isEmpty() && manaNames.size() == abilityNames.size();
                        if (!allMana) {
                            hasPlayableCards = true;
                            break;
                        }
                    }
                }

                if (hasPlayableCards) {
                    // Playable cards available — return so LLM can decide
                    Map<String, Object> result = new HashMap<>();
                    result.put("action_pending", true);
                    result.put("action_type", method.name());
                    result.put("actions_passed", actionsPassed);
                    result.put("has_playable_cards", true);
                    attachUnseenChat(result);
                    return result;
                }

                // No playable cards — auto-pass this priority
                synchronized (actionLock) {
                    if (pendingAction == action) {
                        pendingAction = null;
                    }
                }
                session.sendPlayerBoolean(action.getGameId(), false);
                actionsPassed++;
            }

            // Wait for next action
            long remaining = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remaining <= 0) break;
            synchronized (actionLock) {
                try {
                    actionLock.wait(Math.min(remaining, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Lost response retry: if we sent a response but haven't received
            // a new callback for >25s, the server may have discarded our response
            // due to a race in HumanPlayer.waitResponseOpen() (blocked by slow
            // event dispatch to a disconnected player). Retry once and give it
            // a fresh timeout window to take effect.
            if (retryLastResponseIfLost()) {
                startTime = System.currentTimeMillis();
            }
        }

        // Timeout
        Map<String, Object> result = new HashMap<>();
        result.put("action_pending", false);
        result.put("actions_passed", actionsPassed);
        result.put("timeout", true);
        attachUnseenChat(result);
        return result;
    }

    public Map<String, Object> autoPassUntilEvent(int minNewChars, int timeoutMs) {
        long startTime = System.currentTimeMillis();
        int startLogLength = getGameLogLength();
        int actionsHandled = 0;

        // Snapshot initial game state for change detection
        GameView startView = lastGameView;
        int startTurn = startView != null ? startView.getTurn() : -1;
        Map<String, Integer> startLifeTotals = getLifeTotals(startView);
        Map<String, Integer> startBattlefieldCounts = getBattlefieldCounts(startView);
        Map<String, Integer> startGraveyardCounts = getGraveyardCounts(startView);

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Auto-handle any pending action
            PendingAction action = pendingAction;
            if (action != null) {
                executeDefaultAction();
                actionsHandled++;

                // Check for meaningful game state changes after each action
                GameView currentView = lastGameView;
                if (currentView != null && startView != null) {
                    String changes = describeStateChanges(
                        startTurn, startLifeTotals, startBattlefieldCounts, startGraveyardCounts,
                        currentView
                    );
                    if (changes != null) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("event_occurred", true);
                        result.put("new_log", changes);
                        result.put("actions_taken", actionsHandled);
                        result.put("game_over", activeGames.isEmpty());
                        if (playerDead) result.put("player_dead", true);
                        return result;
                    }
                }
            }

            // Check if enough new game log has accumulated
            int currentLogLength = getGameLogLength();
            if (currentLogLength - startLogLength >= minNewChars) {
                Map<String, Object> result = new HashMap<>();
                result.put("event_occurred", true);
                result.put("new_log", getGameLogSince(startLogLength));
                result.put("actions_taken", actionsHandled);
                result.put("game_over", activeGames.isEmpty());
                if (playerDead) result.put("player_dead", true);
                return result;
            }

            // Wait for new action or log entry (wakes on either)
            long remaining = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remaining <= 0) break;
            synchronized (actionLock) {
                try {
                    actionLock.wait(Math.min(remaining, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Timeout - return state summary so the LLM knows where things stand
        Map<String, Object> result = new HashMap<>();
        String summary = buildStateSummary(lastGameView);
        int currentLogLength = getGameLogLength();
        String newLog = currentLogLength > startLogLength ? getGameLogSince(startLogLength) : "";
        String fullLog = !summary.isEmpty() ? summary + newLog : newLog;

        result.put("event_occurred", !fullLog.isEmpty());
        result.put("new_log", fullLog);
        result.put("actions_taken", actionsHandled);
        result.put("game_over", activeGames.isEmpty());
        if (playerDead) result.put("player_dead", true);
        return result;
    }

    private Map<String, Integer> getLifeTotals(GameView view) {
        Map<String, Integer> totals = new HashMap<>();
        if (view != null) {
            for (PlayerView player : view.getPlayers()) {
                totals.put(player.getName(), player.getLife());
            }
        }
        return totals;
    }

    private Map<String, Integer> getBattlefieldCounts(GameView view) {
        Map<String, Integer> counts = new HashMap<>();
        if (view != null) {
            for (PlayerView player : view.getPlayers()) {
                counts.put(player.getName(), player.getBattlefield().size());
            }
        }
        return counts;
    }

    private Map<String, Integer> getGraveyardCounts(GameView view) {
        Map<String, Integer> counts = new HashMap<>();
        if (view != null) {
            for (PlayerView player : view.getPlayers()) {
                counts.put(player.getName(), player.getGraveyard().size());
            }
        }
        return counts;
    }

    /**
     * Compare current game state to snapshots and describe meaningful changes.
     * Returns null if no interesting changes detected.
     */
    private String describeStateChanges(
        int startTurn,
        Map<String, Integer> startLifeTotals,
        Map<String, Integer> startBattlefieldCounts,
        Map<String, Integer> startGraveyardCounts,
        GameView currentView
    ) {
        StringBuilder changes = new StringBuilder();

        // Turn changed
        if (currentView.getTurn() != startTurn) {
            changes.append("Turn ").append(currentView.getTurn())
                   .append(" (").append(currentView.getActivePlayerName()).append("'s turn)\n");
        }

        // Life total changes
        for (PlayerView player : currentView.getPlayers()) {
            Integer startLife = startLifeTotals.get(player.getName());
            if (startLife != null && startLife != player.getLife()) {
                int diff = player.getLife() - startLife;
                changes.append(player.getName()).append(": ")
                       .append(startLife).append(" -> ").append(player.getLife())
                       .append(" life (").append(diff > 0 ? "+" : "").append(diff).append(")\n");
            }
        }

        // Battlefield changes
        for (PlayerView player : currentView.getPlayers()) {
            int currentCount = player.getBattlefield().size();
            int startCount = startBattlefieldCounts.getOrDefault(player.getName(), 0);
            if (currentCount != startCount) {
                int diff = currentCount - startCount;
                changes.append(player.getName()).append(": ")
                       .append(diff > 0 ? "+" : "").append(diff)
                       .append(" permanents (").append(currentCount).append(" total)\n");
            }
        }

        // Graveyard changes
        for (PlayerView player : currentView.getPlayers()) {
            int currentCount = player.getGraveyard().size();
            int startCount = startGraveyardCounts.getOrDefault(player.getName(), 0);
            if (currentCount != startCount) {
                int diff = currentCount - startCount;
                changes.append(player.getName()).append("'s graveyard: ")
                       .append(diff > 0 ? "+" : "").append(diff)
                       .append(" cards (").append(currentCount).append(" total)\n");
            }
        }

        if (changes.length() == 0) {
            return null;
        }

        // Append current phase info for context
        changes.append("Phase: ").append(currentView.getPhase());
        if (currentView.getStep() != null) {
            changes.append(" - ").append(currentView.getStep());
        }
        changes.append("\n");

        return changes.toString();
    }

    /**
     * Build a brief state summary for timeout returns so the LLM isn't left blind.
     */
    private String buildStateSummary(GameView view) {
        if (view == null) return "";
        StringBuilder summary = new StringBuilder();
        summary.append("Turn ").append(view.getTurn())
               .append(", ").append(view.getPhase());
        if (view.getStep() != null) {
            summary.append(" - ").append(view.getStep());
        }
        summary.append(", ").append(view.getActivePlayerName()).append("'s turn\n");
        for (PlayerView player : view.getPlayers()) {
            summary.append(player.getName()).append(": ").append(player.getLife()).append(" life, ")
                   .append(player.getBattlefield().size()).append(" permanents\n");
        }
        return summary.toString();
    }

    private String getGameLogSince(int offset) {
        synchronized (gameLog) {
            int adjustedOffset = offset - gameLogTrimmedChars;
            if (adjustedOffset >= gameLog.length()) return "";
            // If the caller's reference point was trimmed away, return from the
            // start of the current buffer (oldest surviving entry).
            if (adjustedOffset < 0) adjustedOffset = 0;
            return gameLog.substring(adjustedOffset);
        }
    }

    public Map<String, Object> getGameState() {
        Map<String, Object> state = new HashMap<>();
        GameView gameView = lastGameView;
        if (gameView == null) {
            state.put("available", false);
            state.put("error", "No game state available yet");
            return state;
        }

        state.put("available", true);
        state.put("turn", roundTracker.update(gameView));

        // Phase info
        if (gameView.getPhase() != null) {
            state.put("phase", gameView.getPhase().toString());
        }
        if (gameView.getStep() != null) {
            state.put("step", gameView.getStep().toString());
        }

        state.put("active_player", gameView.getActivePlayerName());
        state.put("priority_player", gameView.getPriorityPlayerName());

        // Players
        List<Map<String, Object>> players = new ArrayList<>();
        UUID gameId = currentGameId; // snapshot volatile to prevent TOCTOU race
        UUID myPlayerId = gameId != null ? activeGames.get(gameId) : null;

        for (PlayerView player : gameView.getPlayers()) {
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("name", player.getName());
            playerInfo.put("life", player.getLife());
            playerInfo.put("library_size", player.getLibraryCount());
            playerInfo.put("hand_size", player.getHandCount());
            playerInfo.put("is_active", player.isActive());

            boolean isMe = player.getPlayerId().equals(myPlayerId);
            playerInfo.put("is_you", isMe);

            // Hand cards (only for our player)
            if (isMe && gameView.getMyHand() != null) {
                List<Map<String, Object>> handCards = new ArrayList<>();
                PlayableObjectsList playable = gameView.getCanPlayObjects();

                for (Map.Entry<UUID, CardView> handEntry : gameView.getMyHand().entrySet()) {
                    CardView card = handEntry.getValue();
                    Map<String, Object> cardInfo = new HashMap<>();
                    cardInfo.put("name", card.getDisplayName());

                    String manaCost = card.getManaCostStr();
                    if (manaCost != null && !manaCost.isEmpty()) {
                        cardInfo.put("mana_cost", manaCost);
                    }
                    cardInfo.put("mana_value", card.getManaValue());

                    if (card.isLand()) {
                        cardInfo.put("is_land", true);
                    }
                    if (card.isCreature() && card.getPower() != null) {
                        cardInfo.put("power", card.getPower());
                        cardInfo.put("toughness", card.getToughness());
                    }
                    if (playable != null && playable.containsObject(handEntry.getKey())) {
                        cardInfo.put("playable", true);
                    }

                    handCards.add(cardInfo);
                }
                playerInfo.put("hand", handCards);
            }

            // Battlefield
            List<Map<String, Object>> battlefield = new ArrayList<>();
            if (player.getBattlefield() != null) {
                for (PermanentView perm : player.getBattlefield().values()) {
                    Map<String, Object> permInfo = new HashMap<>();
                    permInfo.put("name", perm.getDisplayName());
                    permInfo.put("tapped", perm.isTapped());

                    // P/T for creatures
                    if (perm.isCreature()) {
                        permInfo.put("power", perm.getPower());
                        permInfo.put("toughness", perm.getToughness());
                    }

                    // Loyalty for planeswalkers
                    if (perm.isPlaneswalker()) {
                        permInfo.put("loyalty", perm.getLoyalty());
                    }

                    // Counters
                    if (perm.getCounters() != null && !perm.getCounters().isEmpty()) {
                        Map<String, Integer> counters = new HashMap<>();
                        for (CounterView counter : perm.getCounters()) {
                            counters.put(counter.getName(), counter.getCount());
                        }
                        permInfo.put("counters", counters);
                    }

                    // Summoning sickness
                    if (perm.isCreature() && perm.hasSummoningSickness()) {
                        permInfo.put("summoning_sickness", true);
                    }

                    // State-deviation flags: info the LLM can't infer from card name alone
                    if (perm.isToken()) {
                        permInfo.put("token", true);
                        // Include rules for tokens since get_oracle_text can't look them up
                        List<String> rules = perm.getRules();
                        if (rules != null && !rules.isEmpty()) {
                            permInfo.put("rules", rules);
                        }
                    }
                    if (perm.isCopy()) {
                        permInfo.put("copy", true);
                    }
                    if (perm.isMorphed() || perm.isManifested()) {
                        permInfo.put("face_down", true);
                    }

                    battlefield.add(permInfo);
                }
            }
            if (!battlefield.isEmpty()) {
                playerInfo.put("battlefield", battlefield);
            }

            // Graveyard
            List<String> graveyard = new ArrayList<>();
            if (player.getGraveyard() != null) {
                for (CardView card : player.getGraveyard().values()) {
                    graveyard.add(card.getDisplayName());
                }
            }
            if (!graveyard.isEmpty()) {
                playerInfo.put("graveyard", graveyard);
            }

            // Exile
            List<String> exileCards = new ArrayList<>();
            if (player.getExile() != null) {
                for (CardView card : player.getExile().values()) {
                    exileCards.add(card.getDisplayName());
                }
            }
            if (!exileCards.isEmpty()) {
                playerInfo.put("exile", exileCards);
            }

            // Mana pool
            ManaPoolView pool = player.getManaPool();
            if (pool != null) {
                int total = pool.getRed() + pool.getGreen() + pool.getBlue()
                          + pool.getWhite() + pool.getBlack() + pool.getColorless();
                if (total > 0) {
                    Map<String, Integer> mana = new HashMap<>();
                    if (pool.getRed() > 0) mana.put("R", pool.getRed());
                    if (pool.getGreen() > 0) mana.put("G", pool.getGreen());
                    if (pool.getBlue() > 0) mana.put("U", pool.getBlue());
                    if (pool.getWhite() > 0) mana.put("W", pool.getWhite());
                    if (pool.getBlack() > 0) mana.put("B", pool.getBlack());
                    if (pool.getColorless() > 0) mana.put("C", pool.getColorless());
                    playerInfo.put("mana_pool", mana);
                }
            }

            // Player counters (poison, etc.)
            if (player.getCounters() != null && !player.getCounters().isEmpty()) {
                Map<String, Integer> counters = new HashMap<>();
                for (CounterView counter : player.getCounters()) {
                    counters.put(counter.getName(), counter.getCount());
                }
                playerInfo.put("counters", counters);
            }

            // Commander info
            if (player.getCommandObjectList() != null && !player.getCommandObjectList().isEmpty()) {
                List<String> commanders = new ArrayList<>();
                for (CommandObjectView cmd : player.getCommandObjectList()) {
                    commanders.add(cmd.getName());
                }
                playerInfo.put("commanders", commanders);
            }

            players.add(playerInfo);
        }
        state.put("players", players);

        // Stack
        List<Map<String, Object>> stack = new ArrayList<>();
        if (gameView.getStack() != null) {
            for (CardView card : gameView.getStack().values()) {
                Map<String, Object> stackItem = new HashMap<>();
                stackItem.put("name", card.getDisplayName());
                stackItem.put("rules", card.getRules());
                if (card.getTargets() != null && !card.getTargets().isEmpty()) {
                    stackItem.put("target_count", card.getTargets().size());
                }
                stack.add(stackItem);
            }
        }
        state.put("stack", stack);

        // Combat
        if (gameView.getCombat() != null && !gameView.getCombat().isEmpty()) {
            List<Map<String, Object>> combatGroups = new ArrayList<>();
            for (CombatGroupView group : gameView.getCombat()) {
                Map<String, Object> groupInfo = new HashMap<>();
                List<Map<String, Object>> attackers = new ArrayList<>();
                for (CardView attacker : group.getAttackers().values()) {
                    Map<String, Object> aInfo = new HashMap<>();
                    aInfo.put("name", attacker.getDisplayName());
                    if (attacker.getPower() != null) {
                        aInfo.put("power", attacker.getPower());
                        aInfo.put("toughness", attacker.getToughness());
                    }
                    attackers.add(aInfo);
                }
                groupInfo.put("attackers", attackers);
                List<Map<String, Object>> blockers = new ArrayList<>();
                for (CardView blocker : group.getBlockers().values()) {
                    Map<String, Object> bInfo = new HashMap<>();
                    bInfo.put("name", blocker.getDisplayName());
                    if (blocker.getPower() != null) {
                        bInfo.put("power", blocker.getPower());
                        bInfo.put("toughness", blocker.getToughness());
                    }
                    blockers.add(bInfo);
                }
                if (!blockers.isEmpty()) {
                    groupInfo.put("blockers", blockers);
                }
                groupInfo.put("blocked", group.isBlocked());
                groupInfo.put("defending", group.getDefenderName());
                combatGroups.add(groupInfo);
            }
            state.put("combat", combatGroups);
        }

        return state;
    }

    public Map<String, Object> getMyDecklist() {
        Map<String, Object> result = new HashMap<>();
        DeckCardLists deck = this.deckList;
        if (deck == null) {
            result.put("error", "No deck loaded");
            return result;
        }

        StringBuilder cards = new StringBuilder();
        for (DeckCardInfo card : deck.getCards()) {
            if (cards.length() > 0) cards.append("\n");
            cards.append(card.getAmount()).append("x ").append(card.getCardName());
        }
        result.put("cards", cards.toString());

        if (!deck.getSideboard().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (DeckCardInfo card : deck.getSideboard()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(card.getAmount()).append("x ").append(card.getCardName());
            }
            result.put("sideboard", sb.toString());
        }

        return result;
    }

    /**
     * Collect all creature subtypes from cards in the deck.
     * Used to filter large GAME_CHOOSE_CHOICE lists (e.g. Herald's Horn).
     */
    private Set<String> getDeckCreatureTypes() {
        Set<String> types = new HashSet<>();
        DeckCardLists deck = this.deckList;
        if (deck == null) return types;
        for (DeckCardInfo card : deck.getCards()) {
            CardInfo info = CardRepository.instance.findCard(card.getCardName());
            if (info != null) {
                for (SubType st : info.getSubTypes()) {
                    if (st.getSubTypeSet() == SubTypeSet.CreatureType) {
                        types.add(st.toString());
                    }
                }
            }
        }
        return types;
    }

    public Map<String, Object> getOracleText(String cardName, String objectId, String[] cardNames) {
        Map<String, Object> result = new HashMap<>();

        boolean hasCardName = cardName != null && !cardName.isEmpty();
        boolean hasObjectId = objectId != null && !objectId.isEmpty();
        boolean hasCardNames = cardNames != null && cardNames.length > 0;

        // Validate: exactly one parameter type should be provided
        int providedCount = (hasCardName ? 1 : 0) + (hasObjectId ? 1 : 0) + (hasCardNames ? 1 : 0);
        if (providedCount != 1) {
            result.put("success", false);
            result.put("error", "Provide exactly one of: card_name, object_id, or card_names");
            return result;
        }

        // Batch lookup by card names
        if (hasCardNames) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (String name : cardNames) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", name);
                CardInfo cardInfo = CardRepository.instance.findCard(name);
                if (cardInfo != null) {
                    entry.put("rules", cardInfo.getRules());
                } else {
                    entry.put("error", "not found");
                }
                results.add(entry);
            }
            result.put("success", true);
            result.put("cards", results);
            return result;
        }

        // Object ID lookup (in-game)
        if (hasObjectId) {
            try {
                UUID uuid = UUID.fromString(objectId);
                CardView cardView = findCardViewById(uuid);
                if (cardView != null) {
                    result.put("success", true);
                    result.put("name", cardView.getDisplayName());
                    result.put("rules", cardView.getRules());
                    return result;
                } else {
                    result.put("success", false);
                    result.put("error", "Object not found in current game state: " + objectId);
                    return result;
                }
            } catch (IllegalArgumentException e) {
                result.put("success", false);
                result.put("error", "Invalid UUID format: " + objectId);
                return result;
            }
        }

        // Card name lookup (database)
        CardInfo cardInfo = CardRepository.instance.findCard(cardName);
        if (cardInfo != null) {
            result.put("success", true);
            result.put("name", cardInfo.getName());
            result.put("rules", cardInfo.getRules());
            return result;
        } else {
            result.put("success", false);
            result.put("error", "Card not found in database: " + cardName);
            return result;
        }
    }

    private CardView findCardViewById(UUID objectId) {
        return findCardViewById(objectId, lastGameView);
    }

    private CardView findCardViewById(UUID objectId, GameView gameView) {
        if (gameView == null) return null;

        // Check player's hand
        CardView found = gameView.getMyHand().get(objectId);
        if (found != null) {
            return found;
        }

        // Check stack
        found = gameView.getStack().get(objectId);
        if (found != null) {
            return found;
        }

        // Check all players' zones
        for (PlayerView player : gameView.getPlayers()) {
            // Check battlefield
            PermanentView permanent = player.getBattlefield().get(objectId);
            if (permanent != null) {
                return permanent;
            }

            // Check graveyard
            found = player.getGraveyard().get(objectId);
            if (found != null) {
                return found;
            }

            // Check exile
            found = player.getExile().get(objectId);
            if (found != null) {
                return found;
            }
        }

        // Check exile zones
        for (ExileView exileZone : gameView.getExile()) {
            for (CardView card : exileZone.values()) {
                if (card.getId().equals(objectId)) {
                    return card;
                }
            }
        }

        return null;
    }

    /**
     * Check if a pending GAME_SELECT is a combat selection (declare attackers or blockers)
     * by inspecting the options map for possibleAttackers/possibleBlockers keys.
     * Returns "attackers", "blockers", or null.
     */
    private String detectCombatSelect(PendingAction action) {
        if (action == null || action.getMethod() != ClientCallbackMethod.GAME_SELECT) {
            return null;
        }
        Object data = action.getData();
        if (data instanceof GameClientMessage) {
            Map<String, Serializable> options = ((GameClientMessage) data).getOptions();
            if (options != null) {
                if (options.containsKey("possibleAttackers")) {
                    return "attackers";
                }
                if (options.containsKey("possibleBlockers")) {
                    return "blockers";
                }
            }
        }
        return null;
    }

    /**
     * Look up a PermanentView by UUID from all players' battlefields.
     */
    private PermanentView findPermanentViewById(UUID objectId, GameView gameView) {
        if (gameView == null) return null;
        for (PlayerView player : gameView.getPlayers()) {
            PermanentView perm = player.getBattlefield().get(objectId);
            if (perm != null) return perm;
        }
        return null;
    }

    public void handleCallback(ClientCallback callback) {
        try {
            callback.decompressData();
            UUID objectId = callback.getObjectId();
            ClientCallbackMethod method = callback.getMethod();
            logger.debug("[" + client.getUsername() + "] Callback received: " + method);

            // Skeleton JSONL dump: log every callback
            if (skeletonLogPath != null) {
                String summary = null;
                if (method == ClientCallbackMethod.GAME_UPDATE || method == ClientCallbackMethod.GAME_UPDATE_AND_INFORM) {
                    summary = buildSkeletonStateSummary();
                } else if (method == ClientCallbackMethod.CHATMESSAGE) {
                    Object chatData = callback.getData();
                    if (chatData instanceof ChatMessage) {
                        ChatMessage chatMsg = (ChatMessage) chatData;
                        summary = chatMsg.getMessageType() + ": " + chatMsg.getMessage();
                    }
                } else if (method == ClientCallbackMethod.GAME_OVER) {
                    summary = "Game over";
                }
                logSkeletonEvent(method, summary);
            }

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
                        // Auto-select when required and only one legal target
                        GameClientMessage targetCallbackMsg = (GameClientMessage) callback.getData();
                        if (targetCallbackMsg.isFlag()) { // required
                            Set<UUID> autoTargets = findValidTargets(targetCallbackMsg);
                            if (autoTargets != null && autoTargets.size() == 1) {
                                UUID onlyTarget = autoTargets.iterator().next();
                                logger.info("[" + client.getUsername() + "] Auto-selecting single mandatory target: " + onlyTarget.toString().substring(0, 8));
                                // Update game view if available
                                GameView gv = targetCallbackMsg.getGameView();
                                if (gv != null) lastGameView = gv;
                                session.sendPlayerUUID(objectId, onlyTarget);
                                trackSentResponse(objectId, ResponseType.UUID, onlyTarget, null);
                                break;
                            }
                        }
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
                    // Try auto-tap first; if it fails, let the LLM choose
                    if (!handleGamePlayManaAuto(objectId, callback)) {
                        if (mcpMode) {
                            storePendingAction(objectId, method, callback);
                        } else {
                            // Non-MCP mode: cancel the payment
                            session.sendPlayerBoolean(objectId, false);
                        }
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
        // Capture GameView if available
        if (data instanceof GameClientMessage) {
            GameView gameView = ((GameClientMessage) data).getGameView();
            if (gameView != null) {
                lastGameView = gameView;
            }
        }
        synchronized (actionLock) {
            pendingAction = new PendingAction(gameId, method, data, message);
            actionLock.notifyAll();
        }
        clearTrackedResponse(); // New callback arrived — server moved on, no retry needed
        logger.debug("[" + client.getUsername() + "] Stored pending action: " + method + " - " + message);
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
            String logEntry = null;
            if (chatMsg.getMessageType() == ChatMessage.MessageType.GAME) {
                logEntry = chatMsg.getMessage();
                // Track land plays for land_drops_used hint.
                // " plays " is exclusive to land plays (spells use "casts", abilities "activates").
                if (logEntry != null && logEntry.contains(" plays ") && logEntry.contains(client.getUsername())) {
                    landsPlayedThisTurn++;
                }
                // Detect when our player has lost the game
                if (!playerDead && logEntry != null && logEntry.contains("has lost the game")
                        && logEntry.contains(client.getUsername())) {
                    playerDead = true;
                    logger.info("[" + client.getUsername() + "] Player death detected from game log");
                }
            } else if (chatMsg.getMessageType() == ChatMessage.MessageType.TALK) {
                // Include player chat so LLM pilots can see each other's messages
                String user = chatMsg.getUsername();
                String msg = chatMsg.getMessage();
                if (user != null && msg != null && !msg.isEmpty()) {
                    logEntry = "[Chat] " + user + ": " + msg;
                    // Buffer chat from other players so pass_priority can surface it
                    if (!user.equals(client.getUsername())) {
                        synchronized (unseenChat) {
                            unseenChat.add(user + ": " + msg);
                            // Cap at 20 to bound memory
                            if (unseenChat.size() > 20) {
                                unseenChat.remove(0);
                            }
                        }
                    }
                }
            }
            if (logEntry != null && !logEntry.isEmpty()) {
                // Fix "TURN X" messages to show game round instead of raw turn count
                Matcher turnMatcher = TURN_MSG_PATTERN.matcher(logEntry);
                if (turnMatcher.find()) {
                    logEntry = "TURN " + roundTracker.getGameRound() + logEntry.substring(turnMatcher.end());
                }
                synchronized (gameLog) {
                    if (gameLog.length() > 0) {
                        gameLog.append("\n");
                    }
                    gameLog.append(logEntry);
                    // Cap buffer size to prevent unbounded heap growth in long games
                    if (gameLog.length() > MAX_GAME_LOG_CHARS) {
                        int excess = gameLog.length() - MAX_GAME_LOG_CHARS;
                        // Trim from front at a newline boundary to avoid cutting mid-line
                        int trimTo = gameLog.indexOf("\n", excess);
                        if (trimTo > 0) {
                            trimTo++; // include the newline itself
                        } else {
                            trimTo = excess;
                        }
                        gameLog.delete(0, trimTo);
                        gameLogTrimmedChars += trimTo;
                    }
                }
                synchronized (actionLock) {
                    actionLock.notifyAll();
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

        // Join the game session (creates GameSessionPlayer on server)
        if (!session.joinGame(gameId)) {
            logger.error("[" + client.getUsername() + "] Failed to join game: " + gameId);
        }

        // Get chat ID for this game and join to receive incoming messages
        session.getGameChatId(gameId).ifPresent(chatId -> {
            gameChatIds.put(gameId, chatId);
            session.joinChat(chatId);
            logger.info("[" + client.getUsername() + "] Joined game chat: " + chatId);
        });

        logger.info("[" + client.getUsername() + "] Game started: gameId=" + gameId + ", playerId=" + playerId);
    }

    private void handleGameInit(UUID gameId, ClientCallback callback) {
        GameView gameView = (GameView) callback.getData();
        lastGameView = gameView;
        logger.info("[" + client.getUsername() + "] Game initialized: " + gameView.getPlayers().size() + " players");
    }

    private void logGameState(UUID gameId, ClientCallback callback) {
        Object data = callback.getData();
        if (data instanceof GameView) {
            GameView gameView = (GameView) data;
            lastGameView = gameView;
            logger.debug("[" + client.getUsername() + "] Game update: turn " + gameView.getTurn() +
                    ", phase " + gameView.getPhase() + ", active player " + gameView.getActivePlayerName());
        } else if (data instanceof GameClientMessage) {
            GameClientMessage message = (GameClientMessage) data;
            GameView gameView = message.getGameView();
            if (gameView != null) {
                lastGameView = gameView;
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

        // Try to find valid targets from multiple sources
        Set<UUID> targets = findValidTargets(message);

        sleepBeforeAction();
        if (required && targets != null && !targets.isEmpty()) {
            UUID firstTarget = selectDeterministicTarget(targets, null);
            logger.info("[" + client.getUsername() + "] Target (required): \"" + message.getMessage() + "\" -> " + firstTarget);
            session.sendPlayerUUID(gameId, firstTarget);
        } else {
            logger.info("[" + client.getUsername() + "] Target (optional): \"" + message.getMessage() + "\" -> CANCEL");
            session.sendPlayerBoolean(gameId, false);
        }
    }

    /**
     * Find valid targets from multiple sources in a GameClientMessage.
     * This handles both standard targeting (message.getTargets()) and
     * card-from-zone selection (options.possibleTargets or cardsView1).
     */
    @SuppressWarnings("unchecked")
    private Set<UUID> findValidTargets(GameClientMessage message) {
        // 1. Try message.getTargets() first (standard targeting)
        Set<UUID> targets = message.getTargets();
        if (targets != null && !targets.isEmpty()) {
            return targets;
        }

        // 2. Try options.get("possibleTargets") (card-from-zone selection)
        Map<String, Serializable> options = message.getOptions();
        if (options != null) {
            Object possibleTargets = options.get("possibleTargets");
            if (possibleTargets instanceof Set) {
                Set<UUID> possible = (Set<UUID>) possibleTargets;
                if (!possible.isEmpty()) {
                    return possible;
                }
            }
        }

        // 3. Fall back to cardsView1.keySet() (cards displayed for selection)
        CardsView cardsView = message.getCardsView1();
        if (cardsView != null && !cardsView.isEmpty()) {
            return cardsView.keySet();
        }

        return null;
    }

    /**
     * Select a deterministic target from a set of valid targets.
     * Prefer the order from choices (if provided), otherwise fall back to lexicographic UUID ordering.
     */
    private UUID selectDeterministicTarget(Set<UUID> targets, List<Object> choices) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }

        if (choices != null && !choices.isEmpty()) {
            for (Object choice : choices) {
                if (choice instanceof UUID) {
                    UUID candidate = (UUID) choice;
                    if (targets.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        UUID selected = null;
        for (UUID candidate : targets) {
            if (selected == null || candidate.toString().compareTo(selected.toString()) < 0) {
                selected = candidate;
            }
        }
        return selected;
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

    private UUID extractPayingForId(String message) {
        // Extract object_id='...' from callback HTML so we can avoid tapping the paid object itself.
        if (message == null) {
            return null;
        }
        int idx = message.indexOf("object_id='");
        if (idx < 0) {
            return null;
        }
        int start = idx + "object_id='".length();
        int end = message.indexOf("'", start);
        if (end <= start) {
            return null;
        }
        try {
            return UUID.fromString(message.substring(start, end));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ManaPoolView getMyManaPoolView(GameView gameView) {
        if (gameView == null) {
            return null;
        }
        PlayerView myPlayer = gameView.getMyPlayer();
        if (myPlayer == null) {
            return null;
        }
        return myPlayer.getManaPool();
    }

    private int getManaPoolCount(ManaPoolView manaPool, ManaType manaType) {
        if (manaPool == null) {
            return 0;
        }
        switch (manaType) {
            case WHITE:
                return manaPool.getWhite();
            case BLUE:
                return manaPool.getBlue();
            case BLACK:
                return manaPool.getBlack();
            case RED:
                return manaPool.getRed();
            case GREEN:
                return manaPool.getGreen();
            case COLORLESS:
                return manaPool.getColorless();
            default:
                return 0;
        }
    }

    private String prettyManaType(ManaType manaType) {
        switch (manaType) {
            case WHITE:
                return "White";
            case BLUE:
                return "Blue";
            case BLACK:
                return "Black";
            case RED:
                return "Red";
            case GREEN:
                return "Green";
            case COLORLESS:
                return "Colorless";
            default:
                return manaType.toString();
        }
    }

    private void addPreferredPoolManaChoice(List<ManaType> orderedChoices, ManaPoolView manaPool, ManaType manaType) {
        if (getManaPoolCount(manaPool, manaType) > 0 && !orderedChoices.contains(manaType)) {
            orderedChoices.add(manaType);
        }
    }

    private boolean hasExplicitManaSymbol(String promptText) {
        if (promptText == null) {
            return false;
        }
        return REGEX_WHITE.matcher(promptText).find()
                || REGEX_BLUE.matcher(promptText).find()
                || REGEX_BLACK.matcher(promptText).find()
                || REGEX_RED.matcher(promptText).find()
                || REGEX_GREEN.matcher(promptText).find()
                || REGEX_COLORLESS.matcher(promptText).find();
    }

    private boolean addExplicitPoolChoices(List<ManaType> orderedChoices, ManaPoolView manaPool, String promptText) {
        if (promptText == null) {
            return false;
        }
        boolean hasExplicitSymbols = false;
        if (REGEX_WHITE.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.WHITE);
        }
        if (REGEX_BLUE.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.BLUE);
        }
        if (REGEX_BLACK.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.BLACK);
        }
        if (REGEX_RED.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.RED);
        }
        if (REGEX_GREEN.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.GREEN);
        }
        if (REGEX_COLORLESS.matcher(promptText).find()) {
            hasExplicitSymbols = true;
            addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.COLORLESS);
        }
        return hasExplicitSymbols;
    }

    private List<ManaType> getPoolManaChoices(GameView gameView, String promptText) {
        ManaPoolView manaPool = getMyManaPoolView(gameView);
        if (manaPool == null) {
            return new ArrayList<>();
        }

        List<ManaType> orderedChoices = new ArrayList<>();
        boolean hasExplicitSymbols = addExplicitPoolChoices(orderedChoices, manaPool, promptText);
        if (hasExplicitSymbols) {
            // If explicit symbols are present (e.g. "{G}"), only offer matching pool mana types.
            return orderedChoices;
        }

        // Generic/no-symbol payment: allow any available pool mana in stable order.
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.WHITE);
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.BLUE);
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.BLACK);
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.RED);
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.GREEN);
        addPreferredPoolManaChoice(orderedChoices, manaPool, ManaType.COLORLESS);

        return orderedChoices;
    }

    private UUID getManaPoolPlayerId(UUID gameId, GameView gameView) {
        if (gameView != null) {
            PlayerView myPlayer = gameView.getMyPlayer();
            if (myPlayer != null && myPlayer.getPlayerId() != null) {
                return myPlayer.getPlayerId();
            }
        }
        return activeGames.get(gameId);
    }

    /**
     * Try to auto-tap a mana source. Returns true if a source was tapped,
     * false if no suitable source was found (caller should fall through to LLM).
     */
    private boolean handleGamePlayManaAuto(UUID gameId, ClientCallback callback) {
        return handleGamePlayManaAuto(gameId, (GameClientMessage) callback.getData());
    }

    private boolean handleGamePlayManaAuto(UUID gameId, GameClientMessage message) {
        GameView gameView = message.getGameView();
        if (gameView != null) {
            lastGameView = gameView;
        }

        String msg = message.getMessage();
        UUID payingForId = extractPayingForId(msg);

        // Find a mana source from canPlayObjects and tap it
        PlayableObjectsList playable = gameView != null ? gameView.getCanPlayObjects() : null;
        if (playable != null && !playable.isEmpty()) {
            // Find the first object that has a mana ability (but skip the object being paid for)
            for (Map.Entry<UUID, PlayableObjectStats> entry : playable.getObjects().entrySet()) {
                UUID objectId = entry.getKey();
                // Don't tap the source we're paying for — it may need {T}/sacrifice as part of its cost
                if (objectId.equals(payingForId)) {
                    continue;
                }
                // Don't re-tap a source whose activation cost already failed to pay
                if (failedManaCasts.contains(objectId)) {
                    continue;
                }
                PlayableObjectStats stats = entry.getValue();
                // Only auto-tap mana abilities that use {T} with no additional mana cost.
                // Non-tap mana abilities (sacrifice, discard, etc.) have strategic cost.
                // Abilities like "{1}, {T}: Add {B}{R}" (Shadowblood Ridge) cost mana to
                // activate — tapping them triggers a sub-payment that can loop infinitely.
                boolean hasTapManaAbility = false;
                for (String name : stats.getAllManaAbilityNames()) {
                    if (name.contains("{T}")) {
                        // Check that the activation cost (before ':') doesn't require mana
                        int colonPos = name.indexOf(':');
                        if (colonPos > 0) {
                            String costPart = name.substring(0, colonPos);
                            if (costPart.matches(".*\\{[0-9WUBRGC]\\}.*")) {
                                continue; // Non-free activation cost — skip
                            }
                        }
                        hasTapManaAbility = true;
                        break;
                    }
                }
                if (hasTapManaAbility) {
                    logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> tapping " + objectId.toString().substring(0, 8));
                    session.sendPlayerUUID(gameId, objectId);
                    return true;
                }
            }
        }

        // Try to spend mana already in pool.
        List<ManaType> poolChoices = getPoolManaChoices(gameView, msg);
        if (!poolChoices.isEmpty()) {
            UUID manaPlayerId = getManaPoolPlayerId(gameId, gameView);
            boolean canAutoSelectPoolType = poolChoices.size() == 1 || hasExplicitManaSymbol(msg);
            if (manaPlayerId != null) {
                if (!canAutoSelectPoolType && mcpMode) {
                    logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> pool has multiple options, waiting for manual choice");
                    return false;
                }
                ManaType manaType = poolChoices.get(0);
                if (canAutoSelectPoolType) {
                    logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> using pool " + manaType.toString());
                } else {
                    logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> using first available pool type " + manaType.toString());
                }
                session.sendPlayerManaType(gameId, manaPlayerId, manaType);
                return true;
            }
            logger.warn("[" + client.getUsername() + "] Mana: couldn't resolve player ID for mana pool payment");
        }

        // No suitable source/pool choice found:
        // - MCP mode: return false so caller stores pending action for manual choice.
        // - potato mode: cancel spell and mark cast as failed to avoid loops.
        if (mcpMode) {
            logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> no auto source available, waiting for manual choice");
            return false;
        }

        logger.info("[" + client.getUsername() + "] Mana: \"" + msg + "\" -> no mana source available, cancelling spell");
        if (payingForId != null) {
            failedManaCasts.add(payingForId);
        }
        session.sendPlayerBoolean(gameId, false);
        return true;
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

        StringBuilder sb = new StringBuilder();
        if (message.getMessages() != null) {
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append(message.getMessages().get(i).defaultValue);
            }
        }

        String result = sb.toString();
        logger.info("[" + client.getUsername() + "] MultiAmount: " + count + " values, defaults -> " + result);
        sleepBeforeAction();
        session.sendPlayerString(gameId, result);
    }

    private void handleGameOver(UUID gameId, ClientCallback callback) {
        GameClientMessage message = (GameClientMessage) callback.getData();
        activeGames.remove(gameId);
        UUID chatId = gameChatIds.remove(gameId);
        if (chatId != null) {
            session.leaveChat(chatId);
        }
        logger.info("[" + client.getUsername() + "] Game over: " + message.getMessage());

        if (mcpMode) {
            // In MCP mode, the external controller manages the lifecycle.
            // Don't auto-disconnect — a new game in the match may start shortly.
            logger.info("[" + client.getUsername() + "] Game ended (MCP mode, waiting for controller)");
        } else if (keepAliveAfterGame) {
            logger.info("[" + client.getUsername() + "] Game ended (staller mode, staying connected)");
        } else if (activeGames.isEmpty()) {
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
