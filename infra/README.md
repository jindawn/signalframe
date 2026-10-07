# Infrastructure boundary

The root docker-compose.yml runs only PostgreSQL, binds its port to loopback and preserves data in a named volume. API and Web run locally using scripts/start-api.sh and scripts/start-web.sh. External queues, search/vector services, cloud deployment and observability stacks are intentionally deferred.
