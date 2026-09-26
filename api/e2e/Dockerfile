# Imagem de EXECUCAO (E2E local) dos processos Platform e Portal: JRE 21, usuario sem privilegio, sem segredo
# embutido (tudo por ambiente). O jar vem do build Maven feito antes pelo run.sh (spring-boot repackage).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S nekkora && adduser -S -G nekkora -H nekkora
ARG JAR
COPY --chown=root:root ${JAR} /app/app.jar
USER nekkora
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
