# [Traccar](https://www.traccar.org)

## Overview

Traccar is an open source GPS tracking system. This repository contains Java-based back-end service. It supports more than 200 GPS protocols and more than 2000 models of GPS tracking devices. Traccar can be used with any major SQL database system. It also provides easy to use [REST API](https://www.traccar.org/traccar-api/).

Other parts of Traccar solution include:

- [Traccar web app](https://github.com/traccar/traccar-web)
- [Traccar Manager app](https://github.com/traccar/traccar-manager)

There is also a set of mobile apps that you can use for tracking mobile devices:

- [Traccar Client app](https://github.com/traccar/traccar-client)

## Features

Some of the available features include:

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms and notifications
- Account and device management
- Email and SMS support

## Build

Please read [build from source documentation](https://www.traccar.org/build/) on the official website.

## Coolify Deploy (Recommended)

This repo includes a production-ready `docker-compose.yaml` for Coolify.

Quick start (single domain):
- Domain: `traccarpro.com.br`
- Backend: this repo (Traccar server + Postgres)
- Frontend: `traccar-web` repo (custom UI) on the same domain
- Proxy paths: `/` -> frontend, `/api` and `/api/socket` -> backend

Steps in Coolify:
1) Create an app from GitHub: `arthurribeiroweb-del/traccar`
2) Build type: Docker Compose
3) Compose file: `docker-compose.yaml`
4) Set environment variables (use strong values):
   - `POSTGRES_PASSWORD`
   - `DATABASE_PASSWORD`
5) Expose ports: `8082` (HTTP) and UDP `5000-5250` if you use trackers
6) Enable Auto Deploy (GitHub pushes deploy automatically)

Notes:
- Volumes are already defined to persist database and logs.
- The Traccar admin UI is handled by the frontend app (see `traccar-web`).

## Team

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## License

    Apache License, Version 2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
