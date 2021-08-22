FROM openjdk:11
RUN mkdir /usr/amony
RUN mkdir /usr/amony/videos
COPY ./web/build /usr/amony/client
COPY ./target/scala-2.13/amony.jar /usr/amony
COPY ./src/main/resources/prod/application.conf /usr/amony
WORKDIR /usr/amony
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS "-Dconfig.file=/usr/amony/application.conf"
ENTRYPOINT ["java", "-jar", "amony.jar"]
