### Dink Path

Both notification types post the same envelope (`sourcePlugin`, `text`, `title`, `imageRequested`, `thumbnail`, `metadata`) — only `metadata` differs below.

JSON sent with `EVERY_CARD` notification (one card at a time):

```json
{
  "metadata": {
    "cardName": "White beret",
    "foil": false,
    "newForCollection": true,
    "rarityTier": "Legendary",
    "score": 8200,
    "category": ["Clothing", "Quest reward"],
    "regions": ["Kandarin"],
    "condition": 91.20,
    "imageUrl": "https://osrs-tcg.net/images/cards/white_beret.webp",
    "inspectUrl": "https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f",
    "pulledAt": "2026-09-12T14:00:00Z"
  }
}
```

JSON sent with `AT_END` notification (whole pack, batched):

```json
{
  "text": "Player opened a booster pack!\n\n**New cards**\n- **[White beret](https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f)** - A (91.20)\n\n**Duplicates**\n- [Dragonfruit pie](https://osrs-tcg.net/inspect/f222a252-64af-4a4d-8957-7b64f7a623ad) - C (42.00)",
  "metadata": {
    "notificationType": "packSummary",
    "newCards": [
      {
        "cardName": "White beret",
        "foil": false,
        "rarityTier": "Legendary",
        "score": 8200,
        "instanceId": "ba766bf5-a99d-4100-835b-458c3722189f",
        "inspectUrl": "https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f",
        "imageUrl": "https://osrs-tcg.net/images/cards/white_beret.webp",
        "category": ["Clothing", "Quest reward"],
        "regions": ["Kandarin"],
        "condition": 91.20,
        "pulledAt": "2026-09-12T14:00:00Z"
      }
    ],
    "duplicates": [
      {
        "cardName": "Dragonfruit pie",
        "foil": false,
        "rarityTier": "Common",
        "score": 300,
        "instanceId": "f222a252-64af-4a4d-8957-7b64f7a623ad",
        "inspectUrl": "https://osrs-tcg.net/inspect/f222a252-64af-4a4d-8957-7b64f7a623ad",
        "imageUrl": "https://osrs-tcg.net/images/cards/dragonfruit_pie.webp",
        "category": ["Cooking", "Food"],
        "regions": ["Tirannwn"],
        "condition": 42.00,
        "pulledAt": "2026-09-12T14:00:05Z"
      }
    ]
  }
}
```

### Webhook Path (non-Dink)

Discord-style embed posted directly to the configured webhook URL(s) — same `"embeds"` shape for both triggers, just different fields populated. No `category`/`regions`/`score` here; those only exist on the Dink metadata.

JSON sent with `EVERY_CARD` notification (one card at a time):

```json
{
  "embeds": [
    {
      "title": "OSRS TCG",
      "url": "https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f",
      "description": "Player just added White beret to their collection!\n[Inspect card](https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f)",
      "color": 15158332,
      "footer": { "text": "Collection score: 128,450 (41.20%), Unique cards: 312 / 800 (41.20%), Unique foil cards: 18 / 800 (2.25%), Opened packs: 96, Total cards: 940, Total foil cards: 22" },
      "image": { "url": "https://osrs-tcg.net/images/cards/white_beret.webp" }
    }
  ]
}
```

JSON sent with `AT_END` notification (whole pack, batched — note `url` is dropped and the stats line lands in `footer` instead of the description):

```json
{
  "embeds": [
    {
      "title": "OSRS TCG",
      "description": "Player opened a booster pack!\n\n**New cards**\n- **[White beret](https://osrs-tcg.net/inspect/ba766bf5-a99d-4100-835b-458c3722189f)** - A (91.20)\n\n**Duplicates**\n- [Dragonfruit pie](https://osrs-tcg.net/inspect/f222a252-64af-4a4d-8957-7b64f7a623ad) - C (42.00)",
      "color": 15158332,
      "footer": { "text": "Collection score: 128,450 (41.20%), Unique cards: 312 / 800 (41.20%), Unique foil cards: 18 / 800 (2.25%), Opened packs: 96, Total cards: 940, Total foil cards: 22" },
      "image": { "url": "https://osrs-tcg.net/images/cards/white_beret.webp" }
    }
  ]
}
```
