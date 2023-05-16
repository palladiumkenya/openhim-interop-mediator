FROM openjdk:8
MAINTAINER PalladiumDevs
COPY target/openhim-mediator-hl7message-handler-1.0.0-jar-with-dependencies.jar app.jar
COPY mediator.properties mediator.properties
COPY mediator-registration-info.json mediator-registration-info.json
ENTRYPOINT ["java","-jar","/app.jar","--conf","mediator.properties","--regConf","mediator-registration-info.json"]