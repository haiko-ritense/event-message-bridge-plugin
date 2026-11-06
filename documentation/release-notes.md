# Release notes

Overzicht van wijzigingen per versie van de Event Message Bridge-plugin.

## 0.0.1

Eerste versie.

- Actie **EMB-event ontvangen** (`receive-emb-event`) op een message start event: de proceskoppeling abonneert het
  proces op een event item van de Event Message Bridge.
- Endpoint `POST /api/v1/plugin/event-message-bridge/event` waarmee de Event Message Bridge een ontvangen event meldt.
  Voor elke koppeling op het event item van het event wordt een dossier gestart; het antwoord bevat per dossier de
  business key (het id van het dossierdocument).
- Een event wordt maar één keer verwerkt: het event-id wordt vastgelegd in de tabel `emb_processed_event`.
- Een verkeerd ingestelde koppeling wordt overgeslagen met een melding in de log, zonder de andere koppelingen te
  blokkeren.
