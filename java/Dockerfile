FROM openjdk:22-slim

WORKDIR /app

RUN apt -y update && apt -y upgrade
RUN apt -y install maven

COPY . .

WORKDIR /app/java

RUN mvn compile
