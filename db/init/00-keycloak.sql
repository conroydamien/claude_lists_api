-- Create Keycloak database and user
-- This runs before other init scripts

CREATE USER keycloak WITH PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
