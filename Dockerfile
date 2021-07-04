FROM openjdk:11
RUN mkdir /usr/myapp
RUN mkdir /usr/myapp/vids
RUN mkdir /usr/myapp/vids/.metube
COPY ./web/build /usr/myapp/client
COPY ./target/scala-2.13/webapp-test-assembly-0.1.0-SNAPSHOT.jar /usr/myapp
WORKDIR /usr/myapp
ENV ENV "prod"
EXPOSE 8080
CMD ["java", "-jar", "webapp-test-assembly-0.1.0-SNAPSHOT.jar"]