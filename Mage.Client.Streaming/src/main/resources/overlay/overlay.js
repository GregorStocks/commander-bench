(function () {
  const params = new URLSearchParams(window.location.search);
  const useMockOnly = params.get("mock") === "1";
  const pollMs = Number(params.get("pollMs") || 700);
  const forcePositioned = params.get("positions") === "1";
  const isVideoOverlay = document.body.classList.contains("video-overlay");
  const usePositioned = forcePositioned || isVideoOverlay;

  const app = document.getElementById("app");
  const statusLine = document.getElementById("statusLine");
  const playersGrid = document.getElementById("playersGrid");
  const stackSection = document.getElementById("stackSection");
  const positionLayer = document.getElementById("positionLayer");

  const cardPreview = document.getElementById("cardPreview");
  const cardPreviewImage = document.getElementById("cardPreviewImage");
  const cardPreviewName = document.getElementById("cardPreviewName");
  const cardPreviewType = document.getElementById("cardPreviewType");
  const cardPreviewStats = document.getElementById("cardPreviewStats");
  const cardPreviewRules = document.getElementById("cardPreviewRules");

  let requestInFlight = false;

  const inlineMockState = {
    status: "mock",
    updatedAt: new Date().toISOString(),
    gameId: "mock-game",
    turn: 3,
    phase: "MAIN",
    step: "POSTCOMBAT_MAIN",
    activePlayer: "alpha",
    priorityPlayer: "beta",
    stack: [
      {
        id: "s1",
        name: "Lightning Bolt",
        manaCost: "{R}",
        typeLine: "Instant",
        rules: "Lightning Bolt deals 3 damage to any target.",
        imageUrl: "https://api.scryfall.com/cards/named?exact=Lightning%20Bolt&format=image&version=normal",
        tapped: false,
      },
      {
        id: "s2",
        name: "Counterspell",
        manaCost: "{U}{U}",
        typeLine: "Instant",
        rules: "Counter target spell.",
        imageUrl: "https://api.scryfall.com/cards/named?exact=Counterspell&format=image&version=normal",
        tapped: false,
      },
    ],
    layout: {
      sourceWidth: 1920,
      sourceHeight: 1080,
      playAreas: [],
    },
    players: [
      {
        id: "p1",
        name: "alpha",
        life: 31,
        libraryCount: 73,
        handCount: 5,
        isActive: true,
        hasLeft: false,
        counters: [{ name: "poison", count: 1 }],
        commanders: [
          {
            id: "c1",
            name: "Atraxa, Praetors' Voice",
            manaCost: "{1}{G}{W}{U}{B}",
            typeLine: "Legendary Creature - Phyrexian Angel Horror",
            rules: "Flying, vigilance, deathtouch, lifelink\nAt the beginning of your end step, proliferate.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Atraxa%2C%20Praetors%27%20Voice&format=image&version=normal",
            tapped: false,
            layout: { x: 80, y: 640, width: 68, height: 95 },
          },
        ],
        battlefield: [
          {
            id: "c2",
            name: "Sol Ring",
            manaCost: "{1}",
            typeLine: "Artifact",
            rules: "{T}: Add {C}{C}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sol%20Ring&format=image&version=normal",
            tapped: true,
            layout: { x: 480, y: 600, width: 95, height: 136 },
          },
          {
            id: "c3",
            name: "Cultivate",
            manaCost: "{2}{G}",
            typeLine: "Sorcery",
            rules: "Search your library for up to two basic land cards...",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Cultivate&format=image&version=normal",
            tapped: false,
            layout: { x: 586, y: 600, width: 95, height: 136 },
          },
        ],
        hand: [
          {
            id: "c4",
            name: "Swords to Plowshares",
            manaCost: "{W}",
            typeLine: "Instant",
            rules: "Exile target creature. Its controller gains life equal to its power.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Swords%20to%20Plowshares&format=image&version=normal",
            tapped: false,
            layout: { x: 825, y: 850, width: 76, height: 108 },
          },
        ],
        graveyard: [],
        exile: [],
      },
      {
        id: "p2",
        name: "beta",
        life: 26,
        libraryCount: 66,
        handCount: 7,
        isActive: false,
        hasLeft: false,
        counters: [],
        commanders: [],
        battlefield: [
          {
            id: "c5",
            name: "Rhystic Study",
            manaCost: "{2}{U}",
            typeLine: "Enchantment",
            rules: "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Rhystic%20Study&format=image&version=normal",
            tapped: false,
            layout: { x: 1020, y: 210, width: 95, height: 136 },
          },
        ],
        hand: [],
        graveyard: [],
        exile: [],
      },
    ],
  };

  function formatStats(card) {
    const parts = [];
    if (card.power || card.toughness) {
      parts.push((card.power || "?") + "/" + (card.toughness || "?"));
    }
    if (card.loyalty) {
      parts.push("Loyalty " + card.loyalty);
    }
    if (card.defense) {
      parts.push("Defense " + card.defense);
    }
    if (card.damage && Number(card.damage) > 0) {
      parts.push("Damage " + card.damage);
    }
    return parts.join(" | ");
  }

  function showPreview(card) {
    if (!card) {
      return;
    }
    cardPreviewName.textContent = card.name || "";
    cardPreviewType.textContent = card.typeLine || "";
    cardPreviewStats.textContent = formatStats(card);
    cardPreviewRules.textContent = card.rules || "";

    if (card.imageUrl) {
      cardPreviewImage.src = card.imageUrl;
      cardPreviewImage.alt = card.name || "Card";
      cardPreviewImage.style.display = "";
    } else {
      cardPreviewImage.removeAttribute("src");
      cardPreviewImage.style.display = "none";
    }

    cardPreview.classList.remove("hidden");
  }

  function hidePreview() {
    cardPreview.classList.add("hidden");
  }

  function makeZone(title, cards, countHint) {
    const zone = document.createElement("section");
    zone.className = "zone";

    const titleEl = document.createElement("div");
    titleEl.className = "zone-title";
    const count = Array.isArray(cards) ? cards.length : 0;
    const suffix = countHint != null ? countHint : count;
    titleEl.textContent = title + " (" + suffix + ")";
    zone.appendChild(titleEl);

    const row = document.createElement("div");
    row.className = "cards-row";
    zone.appendChild(row);

    if (!cards || cards.length === 0) {
      const empty = document.createElement("span");
      empty.className = "card-chip";
      empty.textContent = "empty";
      row.appendChild(empty);
      return zone;
    }

    cards.forEach((card) => {
      const chip = document.createElement("span");
      chip.className = "card-chip" + (card.tapped ? " tapped" : "");
      chip.textContent = card.name || "Unknown";
      chip.addEventListener("mouseenter", () => showPreview(card));
      chip.addEventListener("mouseleave", hidePreview);
      row.appendChild(chip);
    });

    return zone;
  }

  function renderStack(stack) {
    stackSection.innerHTML = "";
    stackSection.classList.remove("hidden");

    const title = document.createElement("div");
    title.className = "stack-title";
    title.textContent = "Stack" + (stack && stack.length > 0 ? "" : " (empty)");
    stackSection.appendChild(title);

    if (!stack || stack.length === 0) {
      return;
    }

    const row = document.createElement("div");
    row.className = "stack-list";
    stackSection.appendChild(row);

    stack.forEach((card) => {
      const chip = document.createElement("span");
      chip.className = "card-chip";
      chip.textContent = card.name || "Unknown";
      chip.addEventListener("mouseenter", () => showPreview(card));
      chip.addEventListener("mouseleave", hidePreview);
      row.appendChild(chip);
    });

    stackSection.classList.remove("hidden");
  }

  function renderPlayers(players) {
    playersGrid.innerHTML = "";
    if (!players || players.length === 0) {
      return;
    }

    players.forEach((player) => {
      const card = document.createElement("article");
      card.className = "player-card";

      const header = document.createElement("header");
      header.className = "player-header";
      card.appendChild(header);

      const nameEl = document.createElement("div");
      nameEl.className = "player-name";
      if (player.isActive) {
        nameEl.classList.add("active");
      }
      if (player.hasLeft) {
        nameEl.classList.add("left");
      }
      nameEl.textContent = player.name || "Unknown";
      header.appendChild(nameEl);

      const statsEl = document.createElement("div");
      statsEl.className = "player-stats";
      const counters = (player.counters || [])
        .filter((c) => c && Number(c.count) > 0)
        .map((c) => c.name + ":" + c.count);
      const lines = [
        "Life " + (player.life ?? "?"),
        "Library " + (player.libraryCount ?? "?"),
        "Hand " + (player.handCount ?? "?"),
      ];
      if (counters.length > 0) {
        lines.push(counters.join(" "));
      }
      if (player.priorityTimeLeftSecs > 0 || player.timerActive) {
        var secs = player.priorityTimeLeftSecs || 0;
        var m = Math.floor(secs / 60);
        var s = secs % 60;
        lines.push("Clock " + m + ":" + String(s).padStart(2, "0"));
      }
      statsEl.textContent = lines.join(" | ");
      header.appendChild(statsEl);

      card.appendChild(makeZone("Command", player.commanders || []));
      card.appendChild(makeZone("Battlefield", player.battlefield || []));
      card.appendChild(makeZone("Hand", player.hand || [], player.handCount));
      card.appendChild(makeZone("Graveyard", player.graveyard || []));
      card.appendChild(makeZone("Exile", player.exile || []));

      playersGrid.appendChild(card);
    });
  }

  function collectPositionCards(state) {
    const out = [];
    const zoneList = ["commanders", "battlefield", "hand", "graveyard", "exile"];

    (state.players || []).forEach((player) => {
      zoneList.forEach((zone) => {
        (player[zone] || []).forEach((card) => {
          if (card && card.layout) {
            out.push({ card, playerId: player.id, zone, layout: card.layout });
          }
        });
      });
    });

    (state.stack || []).forEach((card) => {
      if (card && card.layout) {
        out.push({ card, playerId: "global", zone: "stack", layout: card.layout });
      }
    });

    return out;
  }

  function renderPositionLayer(state) {
    if (!positionLayer) {
      return false;
    }

    const sourceWidth = Number(state?.layout?.sourceWidth || 0);
    const sourceHeight = Number(state?.layout?.sourceHeight || 0);
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      return false;
    }

    const entries = collectPositionCards(state);
    if (entries.length === 0) {
      return false;
    }

    // Enable positioned mode before measuring; otherwise the layer can report 0x0.
    app.classList.add("positioned-mode");
    positionLayer.classList.remove("hidden");
    playersGrid.classList.add("hidden");
    stackSection.classList.add("hidden");
    positionLayer.innerHTML = "";

    // Prefer measured layer size, but fall back to viewport size if layout
    // hasn't settled yet (prevents false fallback to list mode).
    const layerRect = positionLayer.getBoundingClientRect();
    const layerWidth = layerRect.width > 0 ? layerRect.width : window.innerWidth;
    const layerHeight = layerRect.height > 0 ? layerRect.height : window.innerHeight;
    if (layerWidth <= 0 || layerHeight <= 0) {
      return false;
    }

    const scaleX = layerWidth / sourceWidth;
    const scaleY = layerHeight / sourceHeight;

    entries.forEach((entry) => {
      const layout = entry.layout || {};
      const x = Math.round(Number(layout.x || 0) * scaleX);
      const y = Math.round(Number(layout.y || 0) * scaleY);
      const width = Math.max(4, Math.round(Number(layout.width || 0) * scaleX));
      const height = Math.max(4, Math.round(Number(layout.height || 0) * scaleY));

      if (width < 2 || height < 2) {
        return;
      }

      const hotspot = document.createElement("div");
      hotspot.className = "position-card" + (entry.card.tapped ? " tapped" : "");
      if (width < 42 || height < 16) {
        hotspot.classList.add("small");
      } else {
        hotspot.textContent = entry.card.name || "";
      }

      hotspot.style.left = x + "px";
      hotspot.style.top = y + "px";
      hotspot.style.width = width + "px";
      hotspot.style.height = height + "px";
      hotspot.addEventListener("mouseenter", () => showPreview(entry.card));
      hotspot.addEventListener("mouseleave", hidePreview);
      positionLayer.appendChild(hotspot);
    });

    return true;
  }

  function disablePositionLayer() {
    app.classList.remove("positioned-mode");
    if (positionLayer) {
      positionLayer.classList.add("hidden");
      positionLayer.innerHTML = "";
    }
    playersGrid.classList.remove("hidden");
  }

  function renderState(state) {
    const turn = state.turn != null ? ("Turn " + state.turn) : "Turn ?";
    const phase = state.phase || "?";
    const step = state.step || "?";
    const active = state.activePlayer || "?";
    const priority = state.priorityPlayer || "?";
    statusLine.textContent = turn + " | " + phase + "/" + step + " | Active " + active + " | Priority " + priority;

    if (usePositioned) {
      const rendered = renderPositionLayer(state);
      if (!rendered) {
        disablePositionLayer();
        renderPlayers(state.players || []);
      }
    } else {
      disablePositionLayer();
      renderPlayers(state.players || []);
    }

    // Always render the stack section (works in both positioned and list modes)
    renderStack(state.stack || []);
  }

  async function fetchStateFromServer() {
    const response = await fetch("/api/state", { cache: "no-store" });
    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }
    return response.json();
  }

  async function fetchMockStateFromServer() {
    const response = await fetch("/api/mock-state", { cache: "no-store" });
    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }
    return response.json();
  }

  async function tick() {
    if (requestInFlight) {
      return;
    }
    requestInFlight = true;

    try {
      let state;
      if (useMockOnly) {
        try {
          state = await fetchMockStateFromServer();
        } catch (_e) {
          state = inlineMockState;
        }
      } else {
        state = await fetchStateFromServer();
        if (state && state.status === "waiting") {
          statusLine.textContent = "Waiting for game state...";
        }
      }
      renderState(state || inlineMockState);
    } catch (_err) {
      if (useMockOnly) {
        renderState(inlineMockState);
      } else {
        statusLine.textContent = "Overlay server unavailable, retrying...";
      }
    } finally {
      requestInFlight = false;
    }
  }

  window.addEventListener("blur", hidePreview);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      hidePreview();
    }
  });
  window.addEventListener("resize", () => {
    if (!usePositioned) {
      return;
    }
    tick();
  });

  tick();
  window.setInterval(tick, Math.max(250, pollMs));
})();
