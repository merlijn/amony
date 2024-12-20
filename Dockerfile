FROM europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/base:latest

# create directories
RUN mkdir -p /amony/app
RUN mkdir -p /amony/data

# copy files
COPY ./web-client/dist /amony/app/web-client
COPY ./server/web-server/target/scala-3.3.4/amony.jar /amony/app
COPY ./server/web-server/src/main/resources/prod/application.conf /amony

WORKDIR /usr/amony
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS "-Dconfig.file=/amony/application.conf"
ENV AMONY_HOME "/amony"
#ENV AMONY_CONFIG_FILE "/amony/application.conf"
ENTRYPOINT ["java", "-jar", "/amony/app/amony.jar"]
