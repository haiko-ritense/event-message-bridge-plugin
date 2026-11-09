# Plugin Documentation

## Overview

Starts a case when the Event Message Bridge (EMB) receives an event. EMB listens to
event sources (Azure Event Hub, Kafka, RabbitMQ) through a Camel route per *event item*. A process link on a BPMN
message start event subscribes that process to one event item: every event EMB reports for it starts a case of the
case definition the process belongs to.

Matching uses only the event item id on the process link, never the BPMN message name.

## Dependencies

### Backend

```kotlin
dependencies {
    implementation("com.ritense.valtimoplugins:event-message-bridge:0.0.1")
}
```

### Frontend

```json
{
  "dependencies": {
    "@valtimo-plugins/event-message-bridge": "0.0.1"
  }
}
```

In your `app.module.ts`:

```typescript
import {
    EventMessageBridgePluginModule, eventMessageBridgePluginSpecification,
} from '@valtimo-plugins/event-message-bridge';

@NgModule({
    imports: [
        EventMessageBridgePluginModule,
    ],
    providers: [
        {
            provide: PLUGINS_TOKEN,
            useValue: [
                eventMessageBridgePluginSpecification,
            ]
        }
    ]
})
```

## Configuration

The plugin has no properties; a configuration only needs a name.

## Actions

### Receive EMB event (`receive-emb-event`)

Link it to a **message start event** of a case process.

| Property      | Required | Description                                                                 |
|---------------|----------|-----------------------------------------------------------------------------|
| `eventItemId` | Yes      | The id of the event item in EMB, shown on the event item's detail page in EMB |

The action never runs as a process step: the process link is the subscription.

## Requirements

- GZAC with external plugin support: EMB is added to GZAC as an external plugin app by URL, and authenticates with
  the service token GZAC pushes to it.
- The process must belong to a case definition: the plugin only starts cases, not system processes.

## Setting up the Event Message Bridge

1. Add EMB to GZAC as an external plugin app and create a configuration for it. EMB's manifest asks for the endpoint
   grant `POST /api/v1/plugin/event-message-bridge/event`; accept it. On an existing configuration, accept the
   updated grants on its edit screen.
2. In EMB, create an event origin and an event item. Copy the event item's id from its detail page into the
   process link's **Event item ID**.
3. End the event item's Camel route with EMB's built-in step, and give each event an id that stays the same when
   the source delivers it again, so the plugin can recognise a duplicate. For an Azure Event Hub:

   ```yaml
         steps:
           - setHeader:
               name: embEventId
               simple: "${header.CamelAzureEventHubsPartitionId}-${header.CamelAzureEventHubsSequenceNumber}"
           - to: "direct:emb:start-case"
   ```

   EMB reports the event to this plugin and stores one mapped event per started case, with the case's business key.

## Receiving events

EMB reports each event with:

```
POST /api/v1/plugin/event-message-bridge/event
{ "eventId": "…", "eventItemId": "…", "data": { … } }
```

The endpoint requires the `ROLE_ADMIN` authority. EMB calls it with the service token GZAC pushes to it as an
external plugin app; GZAC gives that token `ROLE_ADMIN`, limited to the endpoints EMB's manifest grants. Any
administrator can call the endpoint too, which is useful for testing (see [Example Application](example-application.md)).

For every `receive-emb-event` link on a message start event whose `eventItemId` is the event's, the plugin:

1. Checks the start event belongs to the process itself (not an event subprocess) and the process definition is not
   suspended.
2. Finds the case definition linked to the process, and skips the link unless that is the active version and it may
   initialise a document.
3. Creates an empty case document.
4. Starts the process by correlating the start event's message to that process definition, with the document id as
   business key. Correlating, rather than starting by key, makes the process start at this start event even when it also
   has a plain one.
5. Associates the process instance with the document, unless Valtimo's start listener already did.

A link that cannot be started is skipped with a log line; the other links still start their cases.

The response lists the started cases; `businessKey` is the case document id:

```json
{
  "duplicate": false,
  "startedCases": [
    { "processLinkId": "…", "processInstanceId": "…", "businessKey": "…" }
  ]
}
```

### Process variables

| Variable         | Description                         |
|------------------|-------------------------------------|
| `embEventId`     | The event's id                      |
| `embEventItemId` | The EMB event item                  |
| `embEventData`   | The event payload, when EMB sent one |

## Database

The plugin creates an `emb_processed_event` table. An event id is recorded before its cases are started, so a
delivery of the same event again starts nothing and returns `"duplicate": true`. When starting a case fails with an
error, the record rolls back with it, and EMB can send the event again.

| Column         | Description                     |
|----------------|---------------------------------|
| `event_id`     | Primary key. The EMB event id.  |
| `processed_at` | When the event was handled.     |
