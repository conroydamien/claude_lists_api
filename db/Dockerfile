FROM docker.io/library/postgres:16

ENV POSTGRES_USER=claude_lists
ENV POSTGRES_PASSWORD=claude_lists
ENV POSTGRES_DB=claude_lists

COPY init/ /docker-entrypoint-initdb.d/
