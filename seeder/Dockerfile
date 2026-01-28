FROM docker.io/library/alpine:3.19

RUN apk add --no-cache curl

COPY seed.sh /seed.sh
RUN chmod +x /seed.sh

CMD ["/seed.sh"]
