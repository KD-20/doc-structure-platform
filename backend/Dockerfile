# syntax=docker/dockerfile:1

# --- Stage 1: build the React frontend ---
FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# --- Stage 2: build the Spring Boot backend, embedding the built frontend as static resources ---
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# --- Stage 3: runtime ---
FROM eclipse-temurin:21-jre-jammy
# tesseract-ocr: Tika's OCR parser shells out to the `tesseract` binary for scanned
# PDFs/images. Without it, image-only content just yields little/no text rather than
# failing — see TikaTextExtractor's javadoc — but installing it here means OCR actually
# works out of the box in the deployed container even though it must be installed
# separately for local (non-Docker) dev.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend-build /backend/target/platform.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
