FROM openjdk:11
RUN mkdir /usr/amony
RUN mkdir /usr/amony/videos
COPY ./web/build /usr/amony/client
COPY ./target/scala-2.13/amony.jar /usr/amony
COPY ./src/main/resources/prod/application.conf /usr/amony/amony.conf
WORKDIR /usr/amony
EXPOSE 8080
CMD ["java", "-jar", "amony.jar", "-Dconfig.file=/usr/amony/amony.conf"]
