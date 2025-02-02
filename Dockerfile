# Stage 1: Сборка приложения
FROM clojure:openjdk-17-lein AS builder

WORKDIR /app

# Копируем только файлы, необходимые для зависимостей
COPY project.clj /app/
RUN lein deps

# Копируем исходный код
COPY . /app/

# Собираем uberjar
RUN lein uberjar

# Stage 2: Рантайм
FROM eclipse-temurin:17-jre-jammy

# Создаем непривилегированного пользователя
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Создаем директории и настраиваем права
WORKDIR /app
RUN mkdir -p /app/resources && chown -R appuser:appgroup /app

# Копируем только собранный jar из предыдущего этапа (содержит все ресурсы)
COPY --from=builder /app/target/partymanager-*-standalone.jar /app/partymanager.jar

# Переключаемся на непривилегированного пользователя
USER appuser

# Определяем переменные окружения
ENV TELEGRAM_TOKEN=""
ENV DOMAIN_URL=""
ENV PORT=3001

# Создаем volume для state.json
VOLUME ["/app/resources"]

# Открываем порт
EXPOSE ${PORT}

# Настраиваем HEALTHCHECK
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/ || exit 1

# Запускаем приложение
CMD ["java", "-jar", "/app/partymanager.jar"]
