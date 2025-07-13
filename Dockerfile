FROM alpine:latest
RUN apk update
RUN apk add bash zsh openjdk11 maven nodejs
RUN mkdir ./opt/data
CMD ["zsh"]
