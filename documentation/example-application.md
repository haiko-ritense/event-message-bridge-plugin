# Example Application

This project also contains a working example application which is meant to showcase the plugin.

## Running the example application

All commands below should be run from the **project root** directory.

### Prerequisites

- Java 21
- [Docker (Desktop)](https://www.docker.com/products/docker-desktop/)

### Start docker

Make sure docker is running.

Start with gradle script:

```shell
./gradlew :backend:app:composeUp
```

### Start backend

By gradle script:

```shell
./gradlew :backend:app:bootRun
```

### Start frontend

```shell
nvm use 20
npm run clean
npm install
npm run build
npm start
```

### Keycloak users

The example application has a few test users that are preconfigured.

| Name         | Role           | Username  | Password  |
|--------------|----------------|-----------|-----------|
| James Vance  | ROLE_USER      | user      | user      |
| Asha Miller  | ROLE_ADMIN     | admin     | admin     |
| Morgan Finch | ROLE_DEVELOPER | developer | developer |

## Example case

The example case `example` shows the plugin at work. Its process `example-process` has two start events:

- **Process started**: a plain start event with the start form, to start a case by hand.
- **EMB event received**: a message start event linked to the plugin's **Receive EMB event** action. The plugin
  configuration `Event Message Bridge (Autodeployed)` is created at startup.

The link's `eventItemId` is a placeholder (`00000000-0000-0000-0000-000000000000`). Replace it, in
`backend/app/src/main/resources/config/case/example/1-0-0/process-link/example-process.process-link.json` or in the
process link editor, with the id of an event item in your Event Message Bridge.

To try it without the Event Message Bridge, report an event yourself with an admin user's token:

```shell
curl -X POST http://localhost:8080/api/v1/plugin/event-message-bridge/event \
  -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" \
  -d '{"eventId": "test-1", "eventItemId": "00000000-0000-0000-0000-000000000000", "data": {"hello": "world"}}'
```

The response lists the started case. Its summary tab shows the event id and event item. Sending the same `eventId`
again starts nothing and returns `"duplicate": true`.

## Source code

The source code is split up into two modules:

1. [Frontend](/frontend)
2. [Backend](/backend)
