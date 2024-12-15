FROM europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/base:latest

# create directories
RUN mkdir /usr/amony
RUN mkdir /usr/amony/certs
RUN mkdir /usr/amony/videos

# copy files
COPY ./web-client/dist /usr/amony/web-client
COPY ./server/web-server/target/scala-3.3.4/amony.jar /usr/amony
COPY ./server/web-server/src/main/resources/prod/application.conf /usr/amony

WORKDIR /usr/amony
EXPOSE 80
ENV JAVA_TOOL_OPTIONS "-Dconfig.file=/usr/amony/application.conf"
ENV AMONY_HOME "/usr/amony"
ENTRYPOINT ["java", "-jar", "amony.jar"]
