# Development login

For local development only, when `ENVIRONMENT` is not `production` and `ADMIN_PASSWORD` is not set, the API creates this initial account on startup if it does not already exist:

- Username: `admin`
- Password: `ChangeMe123!`

The Android login screen requires the deployed API base URL (HTTPS). These credentials do not create or deploy a server by themselves.

## Before production

1. Set `ENVIRONMENT=production`.
2. Set a unique, strong `ADMIN_PASSWORD` in the hosting provider's secret/environment settings.
3. Set a long random `SECRET_KEY` and a persistent `DATABASE_URL`.
4. Never use the development password in production. Existing users are not overwritten when environment variables change; manage existing credentials through a secure administrative process.

The development default is intentionally for testing only. Do not expose a development server with this account to the public internet.
