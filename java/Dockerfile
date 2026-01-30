FROM eclipse-temurin:22-jdk-jammy

WORKDIR /app/java

RUN apt -y update && apt -y upgrade
RUN apt -y install maven

# 必要なファイルのみをコピー
COPY java/pom.xml .
COPY java/src ./src
COPY java/userDic.csv ./userDic.csv
COPY java/api_key.env ./api_key.env

RUN mvn compile
