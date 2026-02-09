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
    turn: 7,
    phase: "MAIN",
    step: "POSTCOMBAT_MAIN",
    activePlayer: "Krenko",
    priorityPlayer: "Atraxa",
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
        name: "Krenko",
        life: 34,
        libraryCount: 78,
        handCount: 4,
        isActive: true,
        hasLeft: false,
        timerActive: true,
        priorityTimeLeftSecs: 1050,
        counters: [{ name: "poison", count: 2 }],
        commanders: [
          {
            id: "c-krenko",
            name: "Krenko, Mob Boss",
            manaCost: "{2}{R}{R}",
            typeLine: "Legendary Creature - Goblin Warrior",
            rules: "{T}: Create X 1/1 red Goblin creature tokens, where X is the number of Goblins you control.",
            power: "3",
            toughness: "3",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Krenko%2C%20Mob%20Boss&format=image&version=normal",
            tapped: false,
          },
        ],
        battlefield: [
          {
            id: "bf-k1",
            name: "Goblin Warchief",
            manaCost: "{1}{R}{R}",
            typeLine: "Creature - Goblin Warrior",
            power: "2",
            toughness: "2",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Goblin%20Warchief&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-k2",
            name: "Skirk Prospector",
            manaCost: "{R}",
            typeLine: "Creature - Goblin",
            power: "1",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Skirk%20Prospector&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-k3",
            name: "Sol Ring",
            manaCost: "{1}",
            typeLine: "Artifact",
            rules: "{T}: Add {C}{C}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sol%20Ring&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-k4",
            name: "Mountain",
            typeLine: "Basic Land - Mountain",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Mountain&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-k5",
            name: "Mountain",
            typeLine: "Basic Land - Mountain",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Mountain&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-k6",
            name: "Goblin Token",
            typeLine: "Token Creature - Goblin",
            power: "1",
            toughness: "1",
            tapped: true,
          },
          {
            id: "bf-k7",
            name: "Goblin Token",
            typeLine: "Token Creature - Goblin",
            power: "1",
            toughness: "1",
            tapped: true,
          },
        ],
        hand: [],
        graveyard: [
          {
            id: "gy-k1",
            name: "Mogg Fanatic",
            manaCost: "{R}",
            typeLine: "Creature - Goblin",
            power: "1",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Mogg%20Fanatic&format=image&version=normal",
            tapped: false,
          },
          {
            id: "gy-k2",
            name: "Goblin Grenade",
            manaCost: "{R}",
            typeLine: "Sorcery",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Goblin%20Grenade&format=image&version=normal",
            tapped: false,
          },
        ],
        exile: [],
      },
      {
        id: "p2",
        name: "Atraxa",
        life: 41,
        libraryCount: 71,
        handCount: 6,
        isActive: false,
        hasLeft: false,
        counters: [],
        commanders: [
          {
            id: "c-atraxa",
            name: "Atraxa, Praetors' Voice",
            manaCost: "{1}{G}{W}{U}{B}",
            typeLine: "Legendary Creature - Phyrexian Angel Horror",
            rules: "Flying, vigilance, deathtouch, lifelink\nAt the beginning of your end step, proliferate.",
            power: "4",
            toughness: "4",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Atraxa%2C%20Praetors%27%20Voice&format=image&version=normal",
            tapped: false,
          },
        ],
        battlefield: [
          {
            id: "bf-a1",
            name: "Rhystic Study",
            manaCost: "{2}{U}",
            typeLine: "Enchantment",
            rules: "Whenever an opponent casts a spell, you may draw a card unless that player pays {1}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Rhystic%20Study&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-a2",
            name: "Deepglow Skate",
            manaCost: "{4}{U}",
            typeLine: "Creature - Fish",
            power: "3",
            toughness: "3",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Deepglow%20Skate&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-a3",
            name: "Sol Ring",
            manaCost: "{1}",
            typeLine: "Artifact",
            rules: "{T}: Add {C}{C}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sol%20Ring&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-a4",
            name: "Astral Cornucopia",
            manaCost: "{X}{X}{X}",
            typeLine: "Artifact",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Astral%20Cornucopia&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-a5",
            name: "Breeding Pool",
            typeLine: "Land - Forest Island",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Breeding%20Pool&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-a6",
            name: "Command Tower",
            typeLine: "Land",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Command%20Tower&format=image&version=normal",
            tapped: false,
          },
        ],
        hand: [],
        graveyard: [
          {
            id: "gy-a1",
            name: "Swords to Plowshares",
            manaCost: "{W}",
            typeLine: "Instant",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Swords%20to%20Plowshares&format=image&version=normal",
            tapped: false,
          },
        ],
        exile: [
          {
            id: "ex-a1",
            name: "Ichor Rats",
            manaCost: "{1}{B}{B}",
            typeLine: "Creature - Phyrexian Rat",
            power: "2",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Ichor%20Rats&format=image&version=normal",
            tapped: false,
          },
        ],
      },
      {
        id: "p3",
        name: "Meren",
        life: 28,
        libraryCount: 68,
        handCount: 5,
        isActive: false,
        hasLeft: false,
        counters: [{ name: "experience", count: 3 }],
        commanders: [
          {
            id: "c-meren",
            name: "Meren of Clan Nel Toth",
            manaCost: "{2}{B}{G}",
            typeLine: "Legendary Creature - Human Shaman",
            rules: "Whenever another creature you control dies, you get an experience counter.\nAt the beginning of your end step, choose target creature card in your graveyard. If that card's mana value is less than or equal to the number of experience counters you have, return it to the battlefield. Otherwise, put it into your hand.",
            power: "3",
            toughness: "4",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Meren%20of%20Clan%20Nel%20Toth&format=image&version=normal",
            tapped: false,
          },
        ],
        battlefield: [
          {
            id: "bf-m1",
            name: "Sakura-Tribe Elder",
            manaCost: "{1}{G}",
            typeLine: "Creature - Snake Shaman",
            power: "1",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sakura-Tribe%20Elder&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-m2",
            name: "Spore Frog",
            manaCost: "{G}",
            typeLine: "Creature - Frog",
            power: "1",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Spore%20Frog&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-m3",
            name: "Grave Pact",
            manaCost: "{1}{B}{B}{B}",
            typeLine: "Enchantment",
            rules: "Whenever a creature you control dies, each other player sacrifices a creature.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Grave%20Pact&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-m4",
            name: "Sol Ring",
            manaCost: "{1}",
            typeLine: "Artifact",
            rules: "{T}: Add {C}{C}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sol%20Ring&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-m5",
            name: "Swamp",
            typeLine: "Basic Land - Swamp",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Swamp&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-m6",
            name: "Forest",
            typeLine: "Basic Land - Forest",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Forest&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-m7",
            name: "Overgrown Tomb",
            typeLine: "Land - Swamp Forest",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Overgrown%20Tomb&format=image&version=normal",
            tapped: false,
          },
        ],
        hand: [],
        graveyard: [
          {
            id: "gy-m1",
            name: "Fleshbag Marauder",
            manaCost: "{2}{B}",
            typeLine: "Creature - Zombie Warrior",
            power: "3",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Fleshbag%20Marauder&format=image&version=normal",
            tapped: false,
          },
          {
            id: "gy-m2",
            name: "Plaguecrafter",
            manaCost: "{2}{B}",
            typeLine: "Creature - Human Shaman",
            power: "3",
            toughness: "2",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Plaguecrafter&format=image&version=normal",
            tapped: false,
          },
          {
            id: "gy-m3",
            name: "Merciless Executioner",
            manaCost: "{2}{B}",
            typeLine: "Creature - Orc Warrior",
            power: "3",
            toughness: "1",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Merciless%20Executioner&format=image&version=normal",
            tapped: false,
          },
        ],
        exile: [],
      },
      {
        id: "p4",
        name: "Talrand",
        life: 37,
        libraryCount: 74,
        handCount: 8,
        isActive: false,
        hasLeft: false,
        counters: [{ name: "poison", count: 1 }],
        commanders: [
          {
            id: "c-talrand",
            name: "Talrand, Sky Summoner",
            manaCost: "{2}{U}{U}",
            typeLine: "Legendary Creature - Merfolk Wizard",
            rules: "Whenever you cast an instant or sorcery spell, create a 2/2 blue Drake creature token with flying.",
            power: "2",
            toughness: "2",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Talrand%2C%20Sky%20Summoner&format=image&version=normal",
            tapped: false,
          },
        ],
        battlefield: [
          {
            id: "bf-t1",
            name: "Drake Token",
            typeLine: "Token Creature - Drake",
            rules: "Flying",
            power: "2",
            toughness: "2",
            tapped: false,
          },
          {
            id: "bf-t2",
            name: "Drake Token",
            typeLine: "Token Creature - Drake",
            rules: "Flying",
            power: "2",
            toughness: "2",
            tapped: true,
          },
          {
            id: "bf-t3",
            name: "Propaganda",
            manaCost: "{2}{U}",
            typeLine: "Enchantment",
            rules: "Creatures can't attack you unless their controller pays {2} for each creature they control that's attacking you.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Propaganda&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-t4",
            name: "Sol Ring",
            manaCost: "{1}",
            typeLine: "Artifact",
            rules: "{T}: Add {C}{C}.",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Sol%20Ring&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-t5",
            name: "Island",
            typeLine: "Basic Land - Island",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Island&format=image&version=normal",
            tapped: false,
          },
          {
            id: "bf-t6",
            name: "Island",
            typeLine: "Basic Land - Island",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Island&format=image&version=normal",
            tapped: true,
          },
          {
            id: "bf-t7",
            name: "Mystic Sanctuary",
            typeLine: "Land - Island",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Mystic%20Sanctuary&format=image&version=normal",
            tapped: false,
          },
        ],
        hand: [],
        graveyard: [
          {
            id: "gy-t1",
            name: "Pongify",
            manaCost: "{U}",
            typeLine: "Instant",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Pongify&format=image&version=normal",
            tapped: false,
          },
          {
            id: "gy-t2",
            name: "Brainstorm",
            manaCost: "{U}",
            typeLine: "Instant",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Brainstorm&format=image&version=normal",
            tapped: false,
          },
        ],
        exile: [
          {
            id: "ex-t1",
            name: "Reality Shift",
            manaCost: "{1}{U}",
            typeLine: "Instant",
            imageUrl: "https://api.scryfall.com/cards/named?exact=Reality%20Shift&format=image&version=normal",
            tapped: false,
          },
        ],
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

  function computeCardFontSize(width, height) {
    if (width < 42 || height < 16) return 0;
    return Math.max(6, Math.min(11, Math.round(width / 9.5)));
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
      var fontSize = computeCardFontSize(width, height);
      if (fontSize === 0) {
        hotspot.classList.add("small");
      } else {
        hotspot.style.fontSize = fontSize + "px";
        hotspot.textContent = entry.card.name || "";
        var linesAvailable = Math.floor((height - 4) / (fontSize * 1.15));
        if (width < 80 && linesAvailable >= 2) {
          hotspot.classList.add("wrap-name");
          hotspot.style.webkitLineClamp = Math.min(linesAvailable, 3);
        }
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
