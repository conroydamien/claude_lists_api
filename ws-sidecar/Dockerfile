FROM docker.io/library/node:20-slim

WORKDIR /app

COPY package.json ./
RUN npm install --production

COPY . .

EXPOSE 9000

CMD ["node", "server.js"]
