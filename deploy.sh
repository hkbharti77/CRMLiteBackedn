#!/bin/bash
set -e

echo "=========================================="
echo "🚀 Deploying CRMLite Backend with Docker"
echo "=========================================="

# Ensure .env file exists
if [ ! -f .env ]; then
    echo "❌ Error: .env file not found! Please create .env from .env.example before deploying."
    exit 1
fi

# 1. Pull latest code (if in git repo)
if [ -d .git ]; then
    echo "📥 Pulling latest changes from git..."
    git pull origin $(git rev-parse --abbrev-ref HEAD)
fi

# 2. Build and restart containers using Docker Compose
echo "🔨 Building and starting Docker containers..."
docker compose down --remove-orphans
docker compose up --build -d

# 3. Wait for services to become healthy
echo "⏳ Waiting for containers to be ready..."
sleep 5

# 4. Check status
docker compose ps

echo "=========================================="
echo "✅ Deployment completed successfully!"
echo "📡 Logs: Run 'docker compose logs -f backend' to view live application logs."
echo "=========================================="
