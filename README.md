openhim-mediator-emr-interop
===========================
# Compiling and running from source
* `cd openhim-mediator-emr-interop`
* `mvn install`
* `java -jar target/mediator-emr-interop-1.0.0-jar-with-dependencies.jar`

# License
This software is licensed under the Mozilla Public License Version 2.0.

## Running with docker
docker image build -t openhim-mediator-hl7message-handler:latest .
docker run --restart=always openhim-mediator-hl7message-handler:latest