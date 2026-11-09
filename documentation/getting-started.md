# Getting Started

## Prerequisites

- Java 21 (Valtimo 13 requires it)
- [Docker (Desktop)](https://www.docker.com/products/docker-desktop/) for the integration tests and the example application
- Node 20 for the frontend

## Build and test

All commands run from the project root.

```shell
./gradlew build
```

`build` runs the unit and integration tests; the integration tests start a PostgreSQL database with Docker Compose.
To run only the unit tests, without Docker:

```shell
./gradlew :backend:plugin:test --tests '*EventCaseStartServiceTest' -x composeUp -x composeDown
```

Frontend:

```shell
cd frontend
npm install
npm run build
```

## Project layout

| Path                                              | Contents                                                                  |
|---------------------------------------------------|---------------------------------------------------------------------------|
| `backend/plugin`                                  | The plugin: action, event endpoint, case start logic, Liquibase changelog |
| `backend/app`                                     | Example GZAC application with an example case, see [Example Application](example-application.md) |
| `frontend/projects/plugin`                        | The plugin's frontend: configuration and action forms                     |

What the plugin does and how to use it: [Plugin](plugin.md). For background on GZAC plugins, see the
[Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition) documentation.
