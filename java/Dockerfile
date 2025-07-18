FROM openjdk:22-slim

WORKDIR /app

COPY . .

RUN apt -y update && apt -y upgrade
RUN apt -y install curl
