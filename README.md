# PartyManager

PartyManager is a Telegram Web App designed to simplify party management through a convenient bot interface. It allows users to create and manage party groups, handle invitations, and coordinate events directly through Telegram.

## Features

- Telegram Web App integration
- Party group management
- Automatic invite code generation and updates
- Persistent data storage
- Webhook handling for real-time updates

## Prerequisites

- Docker and Docker Compose
- ngrok (for local development)
- Telegram Bot Token (from [@BotFather](https://t.me/BotFather))

## Setup

1. Clone the repository:
```bash
git clone [repository-url]
cd partymanager
```

2. Create `.env` file from the example:
```bash
cp .env.example .env
```

3. Configure your environment variables in `.env`:
```
TELEGRAM_TOKEN=your_bot_token_here
DOMAIN_URL=your_domain_here
PORT=3001  # default port
```

### Local Development with ngrok

1. Start ngrok to create a tunnel to your local server:
```bash
ngrok http 3001
```

2. Copy the HTTPS URL from ngrok output and set it as your `DOMAIN_URL` in `.env`

3. Start the application:
```bash
docker compose up -d
```

### Production Deployment

1. Configure your environment variables with your production domain
2. Run the application:
```bash
docker compose up -d
```

## Configuration

### Environment Variables

- `TELEGRAM_TOKEN`: Your Telegram Bot token (required)
- `DOMAIN_URL`: Your domain URL for webhook configuration (required)
- `PORT`: Server port (default: 3001)

### Data Persistence

The application uses Docker volumes for data persistence:
- Volume: `partymanager_data`
- Mount path: `/app/resources`

## Development

### Project Structure

```
.
├── src/partymanager/
│   ├── api_handler.clj     # Web App API handlers
│   ├── config.clj          # Configuration management
│   ├── core.clj           # Main application entry point
│   ├── message_handler.clj # Telegram message handling
│   ├── state.clj          # Application state management
│   └── storage.clj        # Data persistence
├── resources/
│   └── public/            # Static web resources
├── docker-compose.yml     # Docker Compose configuration
├── Dockerfile            # Docker build configuration
└── project.clj           # Project dependencies
```

### Health Checks

The application includes Docker health checks that verify the server's availability every 30 seconds.

## License

[Your license information here]
